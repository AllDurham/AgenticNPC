package com.agenticnpc.gateway;

import com.agenticnpc.model.LLMResponse;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * LLM 响应容错解析器。
 *
 * 处理模型可能返回的各种非标准格式：
 * 1. 标准 JSON（正常路径）
 * 2. Markdown 代码块包裹的 JSON（```json ... ```）
 * 3. 完全非 JSON（降级返回 empty）
 */
public class LLMResponseParser {

    private final Gson   gson = new Gson();
    private final Logger logger;

    public LLMResponseParser(Logger logger) {
        this.logger = logger;
    }

    /**
     * 解析 LLM 返回的原始内容字符串。
     *
     * @param rawContent LLM content 字段的原始字符串
     * @return 解析成功返回 Optional<LLMResponse>，失败返回 empty
     */
    public Optional<LLMResponse> parse(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            if (rawContent == null) {
                logger.warning("[解析][LLMResponseParser] LLM 返回 null");
            } else {
                logger.warning(String.format(
                    "[解析][LLMResponseParser] LLM 返回纯空白内容 | 长度: %d | 字节: %s",
                    rawContent.length(),
                    rawContent.chars()
                        .mapToObj(c -> String.format("%02X", c))
                        .collect(java.util.stream.Collectors.joining(" "))
                ));
            }
            return Optional.empty();
        }

        try {
            // Step 1: 剥离 Markdown 代码块标志
            String cleaned = rawContent
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*",     "")
                .trim();

            // Step 2: 提取第一个完整 JSON 对象（处理前缀/后缀文本污染）
            String jsonStr = extractFirstJsonObject(cleaned);
            if (jsonStr == null) {
                // 没有找到 JSON 对象，走纯文本兜底
                logger.info("[解析][LLMResponseParser] 未找到 JSON 对象，尝试纯文本兜底");
                return plaintextFallback(rawContent);
            }

            LLMResponse response = gson.fromJson(jsonStr, LLMResponse.class);

            // 必填字段校验
            if (response == null) {
                logger.warning("[解析][LLMResponseParser] Gson 返回 null | 原始内容: " + rawContent);
                return Optional.empty();
            }

            if (response.dialogue() == null || response.dialogue().isBlank()) {
                logger.warning("[解析][LLMResponseParser] 缺少 dialogue 字段 | 原始内容: " + rawContent);
                return Optional.empty();
            }

            return Optional.of(response);

        } catch (JsonSyntaxException e) {
            logger.warning(String.format(
                "[解析][LLMResponseParser] JSON 解析失败 | 原始内容: %s | 错误: %s",
                rawContent, e.getMessage()
            ));
            return plaintextFallback(rawContent);
        }
    }

    // ================================================================
    // JSON 提取
    // ================================================================

    /**
     * 从文本中提取第一个完整的 JSON 对象。
     * 处理场景：
     *   "（微笑）\n{...}"          → 提取 "{...}"
     *   "前缀文本 {...} 后缀文本" → 提取 "{...}"
     *   "{...}"                    → 原样返回
     *
     * 使用括号配对算法，不依赖 regex（不硬编码字段名）。
     *
     * @return 提取到的 JSON 字符串，未找到则返回 null
     */
    String extractFirstJsonObject(String text) {
        if (text == null) return null;

        int start = text.indexOf('{');
        if (start < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escape) {
                escape = false;
                continue;
            }

            if (c == '\\' && inString) {
                escape = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (inString) continue;

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }

        // 括号未闭合，返回从 { 到末尾（让 Gson 报错）
        return null;
    }

    /**
     * 纯文本兜底：模型忘记输出 JSON，直接输出了角色对话。
     */
    private Optional<LLMResponse> plaintextFallback(String rawContent) {
        String trimmed = rawContent.trim();
        if (!trimmed.startsWith("{")) {
            logger.info("[解析][LLMResponseParser] 触发纯文本兜底，已自动包装为普通对话");
            String safeText = trimmed.length() > 200
                ? trimmed.substring(0, 197) + "..." : trimmed;
            return Optional.of(new LLMResponse(safeText, "NONE", null, null));
        }
        return Optional.empty();
    }
}
