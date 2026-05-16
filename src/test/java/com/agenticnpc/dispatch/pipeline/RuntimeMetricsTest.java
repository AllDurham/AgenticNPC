package com.agenticnpc.dispatch.pipeline;

import com.agenticnpc.console.MetricsCollector;
import com.agenticnpc.gateway.LLMResponseParser;
import com.agenticnpc.model.ActionType;
import com.agenticnpc.model.PromptTokenBreakdown;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v1.4-b: Runtime Metrics + Token Breakdown + Fallback 分类测试。
 */
class RuntimeMetricsTest {

    // ---- PromptTokenBreakdown ----

    @Test
    @DisplayName("PromptTokenBreakdown.empty() 全零")
    void breakdownEmpty() {
        PromptTokenBreakdown b = PromptTokenBreakdown.empty();
        assertEquals(0, b.totalTokens());
        assertEquals(0, b.systemTokens());
        assertEquals(0, b.userInputTokens());
    }

    @Test
    @DisplayName("PromptTokenBreakdown totalTokens = 各段之和")
    void breakdownSumConsistent() {
        PromptTokenBreakdown b = new PromptTokenBreakdown(
            100, 20, 30, 5, 15, 10, 40, 50, 8, 278
        );
        int sum = b.systemTokens() + b.profileTokens() + b.summaryTokens()
            + b.emotionTokens() + b.playerInfoTokens() + b.actionTokens()
            + b.formatTokens() + b.historyTokens() + b.userInputTokens();
        assertEquals(b.totalTokens(), sum);
    }

    // ---- MetricsCollector Runtime Metrics ----

    @Test
    @DisplayName("Queue depth 设置和读取")
    void queueDepthTracking() {
        MetricsCollector mc = new MetricsCollector();
        mc.setQueueDepth(5);
        assertEquals(5, mc.snapshot().queueDepth());
        mc.setQueueDepth(0);
        assertEquals(0, mc.snapshot().queueDepth());
    }

    @Test
    @DisplayName("Inflight requests 增减")
    void inflightTracking() {
        MetricsCollector mc = new MetricsCollector();
        mc.recordInflightStart();
        mc.recordInflightStart();
        assertEquals(2, mc.snapshot().inflightRequests());
        mc.recordInflightEnd();
        assertEquals(1, mc.snapshot().inflightRequests());
    }

    @Test
    @DisplayName("LLM latency 记录和平均值")
    void llmLatencyTracking() {
        MetricsCollector mc = new MetricsCollector();
        mc.recordLLMLatency(100);
        mc.recordLLMLatency(300);
        assertEquals(200.0, mc.snapshot().avgLLMLatencyMs(), 0.01);
    }

    @Test
    @DisplayName("LLM timeout 计数")
    void llmTimeoutCounting() {
        MetricsCollector mc = new MetricsCollector();
        mc.recordLLMTimeout();
        mc.recordLLMTimeout();
        mc.recordLLMTimeout();
        assertEquals(3, mc.snapshot().llmTimeoutCount());
    }

    @Test
    @DisplayName("Fallback type 分类计数")
    void fallbackTypeClassification() {
        MetricsCollector mc = new MetricsCollector();
        mc.recordFallbackType("PLAINTEXT_FALLBACK");
        mc.recordFallbackType("PLAINTEXT_FALLBACK");
        mc.recordFallbackType("BRACKET_EXTRACTION");

        var snap = mc.snapshot();
        assertEquals(3, snap.parseFallbacks());
        assertEquals(2L, snap.fallbackTypeCounts().get("PLAINTEXT_FALLBACK"));
        assertEquals(1L, snap.fallbackTypeCounts().get("BRACKET_EXTRACTION"));
    }

    @Test
    @DisplayName("Memory cache hit rate 计算")
    void memoryCacheHitRate() {
        MetricsCollector mc = new MetricsCollector();
        mc.recordMemoryCacheHit();
        mc.recordMemoryCacheHit();
        mc.recordMemoryCacheHit();
        mc.recordMemoryCacheMiss();

        // 3 hits / 4 total = 0.75
        assertEquals(0.75, mc.snapshot().memoryCacheHitRate(), 0.01);
    }

    @Test
    @DisplayName("Summary compression 计数")
    void summaryCompressionCounting() {
        MetricsCollector mc = new MetricsCollector();
        mc.recordSummaryCompression();
        assertEquals(1, mc.snapshot().summaryCompressionCount());
    }

    // ---- LLMResponseParser FallbackType ----

    @Test
    @DisplayName("FallbackType 枚举值完整")
    void fallbackTypeEnum() {
        assertEquals(6, LLMResponseParser.FallbackType.values().length);
        assertNotNull(LLMResponseParser.FallbackType.NONE);
        assertNotNull(LLMResponseParser.FallbackType.MARKDOWN_STRIP);
        assertNotNull(LLMResponseParser.FallbackType.BRACKET_EXTRACTION);
        assertNotNull(LLMResponseParser.FallbackType.PLAINTEXT_FALLBACK);
        assertNotNull(LLMResponseParser.FallbackType.EMPTY_RESPONSE);
        assertNotNull(LLMResponseParser.FallbackType.INVALID_JSON);
    }
}
