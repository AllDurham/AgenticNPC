package com.agenticnpc.dispatch;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.model.PromptPackage;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * LLM HTTP 客户端。
 *
 * 使用 Java 17 内置 HttpClient（零额外依赖）。
 * 强制使用 response_format: json_object 确保结构化输出。
 * 请求级超时：连接 3s，读取 8s。
 */
public class LLMClient {

    private final HttpClient    httpClient;
    private final ConfigManager config;
    private final Gson          gson = new Gson();
    private final Logger        logger;

    public LLMClient(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
            .build();
    }

    /**
     * 异步发送 Prompt 到 LLM API。
     *
     * @param promptPackage 组装好的 Prompt 包
     * @return LLM 返回的 content 字符串（原始 JSON 文本）
     */
    public CompletableFuture<String> sendAsync(PromptPackage promptPackage) {
        String requestBody = buildRequestBody(promptPackage);

        if (config.isDebugMode()) {
            logger.info("[LLMClient] 发送请求 | 模型: " + config.getLLMModel()
                + " | 消息数: " + (promptPackage.history().size() + 2));
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getLLMEndpoint()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.getLLMApiKey())
            .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build();

        return httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (config.isDebugMode()) {
                    logger.info("[LLMClient] 收到响应 | 状态码: " + response.statusCode());
                }

                if (response.statusCode() != 200) {
                    logger.warning("[LLMClient] API 返回非 200 | 状态码: "
                        + response.statusCode() + " | 响应体: " + response.body());
                    throw new LLMException("API 错误，状态码: " + response.statusCode());
                }

                return extractContent(response.body());
            });
    }

    /**
     * 构建符合 OpenAI Chat Completions API 格式的请求体。
     */
    private String buildRequestBody(PromptPackage pkg) {
        List<Map<String, String>> messages = new ArrayList<>();

        // System Prompt
        messages.add(Map.of(
            "role",    "system",
            "content", pkg.systemPrompt()
        ));

        // 历史对话
        for (var msg : pkg.history()) {
            messages.add(Map.of(
                "role",    msg.role(),
                "content", msg.content()
            ));
        }

        // 当前用户输入
        messages.add(Map.of(
            "role",    pkg.userMessage().role(),
            "content", pkg.userMessage().content()
        ));

        // 使用 LinkedHashMap 保证字段顺序（便于调试）
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model",           config.getLLMModel());
        body.put("messages",        messages);
        // body.put("response_format", Map.of("type", "json_object")); // 暂时关闭，诊断 API 兼容性
        body.put("temperature",     config.getLLMTemperature());
        body.put("max_tokens",      config.getLLMMaxTokens());

        String json = gson.toJson(body);

        if (config.isDebugMode()) {
            // 输出发送给模型的完整消息列表（不含 system prompt 避免刷屏）
            logger.info("[LLMClient] 发送消息列表:");
            for (var msg : pkg.history()) {
                logger.info(String.format("  [%s]: %s",
                    msg.role(),
                    msg.content().length() > 80
                        ? msg.content().substring(0, 80) + "..."
                        : msg.content()
                ));
            }
            logger.info(String.format("  [user]: %s", pkg.userMessage().content()));
        }

        return json;
    }

    /**
     * 从 OpenAI 响应体中提取 choices[0].message.content。
     */
    private String extractContent(String responseBody) {
        try {
            JsonObject root    = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray  choices = root.getAsJsonArray("choices");

            if (choices == null || choices.isEmpty()) {
                throw new LLMException("响应中 choices 为空");
            }

            String content = choices.get(0)
                .getAsJsonObject()
                .getAsJsonObject("message")
                .get("content")
                .getAsString();

            if (config.isDebugMode()) {
                // 输出原始字节，暴露不可见字符
                logger.info(String.format(
                    "[LLMClient] 提取 content 成功 | 长度: %d | 原始字节前50: %s | 内容: [%s]",
                    content.length(),
                    bytesToHex(content.substring(0, Math.min(50, content.length()))),
                    content
                ));
            }

            return content;

        } catch (Exception e) {
            logger.warning("[LLMClient] 解析响应体失败: " + e.getMessage()
                + " | 原始响应: " + responseBody);
            throw new LLMException("响应体解析失败: " + e.getMessage());
        }
    }

    private String bytesToHex(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(String.format("%02X ", (int) c));
        }
        return sb.toString();
    }
}
