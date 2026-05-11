package com.agenticnpc.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PromptTokenBreakdownTest {

    @Test
    @DisplayName("空文本 token 估算为 0")
    void emptyTextZeroTokens() {
        assertEquals(0, PromptBuilder.estimateTokens(null));
        assertEquals(0, PromptBuilder.estimateTokens(""));
    }

    @Test
    @DisplayName("纯英文文本 token 估算")
    void englishTextEstimate() {
        // 20 chars / 4 = 5 tokens
        int tokens = PromptBuilder.estimateTokens("hello world test abc");
        assertTrue(tokens >= 4 && tokens <= 7, "Expected ~5, got " + tokens);
    }

    @Test
    @DisplayName("纯中文文本 token 估算")
    void chineseTextEstimate() {
        // 10 CJK chars / 2 = 5 tokens
        int tokens = PromptBuilder.estimateTokens("你好世界测试一二三四五六");
        assertTrue(tokens >= 4 && tokens <= 7, "Expected ~5, got " + tokens);
    }

    @Test
    @DisplayName("混合中英文 token 估算")
    void mixedTextEstimate() {
        // 6 CJK / 2 = 3, 11 ASCII / 4 = ~3, total ~6
        int tokens = PromptBuilder.estimateTokens("你好 hello world 测试");
        assertTrue(tokens >= 4 && tokens <= 10, "Expected ~6, got " + tokens);
    }

    @Test
    @DisplayName("长文本 token 估算大于短文本")
    void longerTextMoreTokens() {
        int short_ = PromptBuilder.estimateTokens("你好");
        int long_  = PromptBuilder.estimateTokens("你好，这是一段很长的文本，用来测试 token 估算的线性增长特性");
        assertTrue(long_ > short_);
    }

    @Test
    @DisplayName("token 估算不为负数")
    void neverNegative() {
        assertTrue(PromptBuilder.estimateTokens("a") >= 0);
        assertTrue(PromptBuilder.estimateTokens("§") >= 0);
    }
}
