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
 *
 * 线程安全：无共享可变状态。FallbackType 通过 ParseResult 返回值传递。
 */
public class LLMResponseParser {

    /** 解析 fallback 原因分类 */
    public enum FallbackType {
        NONE,                // 正常 JSON 解析
        MARKDOWN_STRIP,      // 剥离了 Markdown 代码块
        BRACKET_EXTRACTION,  // 从混合文本中提取了 JSON 对象
        PLAINTEXT_FALLBACK,  // 纯文本兜底
        EMPTY_RESPONSE,      // 空白响应
        INVALID_JSON         // JSON 语法错误
    }

    /** parse() 返回值：包含解析结果和 fallback 类型 */
    public record ParseResult(Optional<LLMResponse> response, FallbackType fallbackType) {
        public boolean isPresent()  { return response.isPresent(); }
        public LLMResponse get()    { return response.get(); }
        public boolean isFallback() { return fallbackType != FallbackType.NONE; }
    }

    private final Gson   gson = new Gson();
    private final Logger logger;

    public LLMResponseParser(Logger logger) {
        this.logger = logger;
    }

    /**
     * 解析 LLM 返回的原始内容字符串。
     * 返回 ParseResult，包含解析结果和 fallback 类型。
     * 无共享可变状态，完全线程安全。
     */
    public ParseResult parse(String rawContent) {
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
            return new ParseResult(Optional.empty(), FallbackType.EMPTY_RESPONSE);
        }

        boolean hadMarkdown = !rawContent.equals(rawContent
            .replaceAll("(?s)```json\\s*", "")
            .replaceAll("(?s)```\\s*",     ""));

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
                return plaintextFallback(rawContent, FallbackType.PLAINTEXT_FALLBACK);
            }

            // 判断 fallback 类型
            FallbackType fallbackType;
            if (!cleaned.equals(jsonStr)) {
                fallbackType = FallbackType.BRACKET_EXTRACTION;
            } else if (hadMarkdown) {
                fallbackType = FallbackType.MARKDOWN_STRIP;
            } else {
                fallbackType = FallbackType.NONE;
            }

            LLMResponse response = gson.fromJson(jsonStr, LLMResponse.class);

            // 必填字段校验
            if (response == null) {
                logger.warning("[解析][LLMResponseParser] Gson 返回 null | 原始内容: " + rawContent);
                return new ParseResult(Optional.empty(), FallbackType.INVALID_JSON);
            }

            if (response.dialogue() == null || response.dialogue().isBlank()) {
                logger.warning("[解析][LLMResponseParser] 缺少 dialogue 字段 | 原始内容: " + rawContent);
                return new ParseResult(Optional.empty(), FallbackType.INVALID_JSON);
            }

            return new ParseResult(Optional.of(response), fallbackType);

        } catch (JsonSyntaxException e) {
            logger.warning(String.format(
                "[解析][LLMResponseParser] JSON 解析失败 | 原始内容: %s | 错误: %s",
                rawContent, e.getMessage()
            ));
            return plaintextFallback(rawContent, FallbackType.INVALID_JSON);
        }
    }

    // ================================================================
    // JSON 提取
    // ================================================================

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

        return null;
    }

    private ParseResult plaintextFallback(String rawContent, FallbackType type) {
        String trimmed = rawContent.trim();
        if (!trimmed.startsWith("{")) {
            logger.info("[解析][LLMResponseParser] 触发纯文本兜底，已自动包装为普通对话");
            String safeText = trimmed.length() > 200
                ? trimmed.substring(0, 197) + "..." : trimmed;
            return new ParseResult(
                Optional.of(new LLMResponse(safeText, "NONE", null, null)),
                type
            );
        }
        return new ParseResult(Optional.empty(), type);
    }
}
