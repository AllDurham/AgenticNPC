package com.agenticnpc.dispatch.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineContextTest {

    @Test
    @DisplayName("PipelineContext 初始化时 traceId 正确传递")
    void traceIdPropagation() {
        PipelineContext ctx = new PipelineContext("abc123def456", null, null);
        assertEquals("abc123def456", ctx.traceId);
    }

    @Test
    @DisplayName("PipelineContext 初始化时 shortCircuit 为 false")
    void initialStateNotShortCircuited() {
        PipelineContext ctx = new PipelineContext("trace1", null, null);
        assertFalse(ctx.shortCircuit);
        assertNull(ctx.failedStage);
        assertNull(ctx.failureReason);
    }

    @Test
    @DisplayName("Stage 输出字段可写可读")
    void stageOutputFields() {
        PipelineContext ctx = new PipelineContext("trace2", null, null);

        ctx.llmRawContent = "raw response";
        ctx.parseFallback = true;

        assertEquals("raw response", ctx.llmRawContent);
        assertTrue(ctx.parseFallback);
    }

    @Test
    @DisplayName("stageLatencyMs 使用 EnumMap")
    void stageLatencyEnumMap() {
        PipelineContext ctx = new PipelineContext("trace3", null, null);

        ctx.stageLatencyMs.put(PipelineStageType.GUARD, 10L);
        ctx.stageLatencyMs.put(PipelineStageType.LLM, 500L);

        assertEquals(10L, ctx.stageLatencyMs.get(PipelineStageType.GUARD));
        assertEquals(500L, ctx.stageLatencyMs.get(PipelineStageType.LLM));
        assertNull(ctx.stageLatencyMs.get(PipelineStageType.PROMPT));
    }
}
