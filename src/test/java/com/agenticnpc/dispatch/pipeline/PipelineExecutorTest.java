package com.agenticnpc.dispatch.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class PipelineExecutorTest {

    private static final Logger logger = Logger.getLogger("test");

    private PipelineContext ctx;

    // Dummy BrainConfig for test
    private static final com.agenticnpc.config.BrainConfig DUMMY_BRAIN = null;

    @BeforeEach
    void setup() {
        // PipelineContext needs traceId, event, brain - use null for event/brain in unit tests
        ctx = new PipelineContext("test123abc", null, DUMMY_BRAIN);
        ctx.pipelineStartMs = System.currentTimeMillis();
    }

    @Test
    @DisplayName("Stage 按顺序执行")
    void stagesExecuteInOrder() {
        List<String> order = new ArrayList<>();

        PipelineStage s1 = new SimpleStage("A", PipelineStageType.GUARD, order);
        PipelineStage s2 = new SimpleStage("B", PipelineStageType.PROMPT, order);
        PipelineStage s3 = new SimpleStage("C", PipelineStageType.LLM, order);

        PipelineExecutor executor = new PipelineExecutor(new PipelineStage[]{s1, s2, s3}, logger);
        executor.run(ctx);

        assertEquals(List.of("A", "B", "C"), order);
        assertNull(ctx.failedStage);
        assertFalse(ctx.shortCircuit);
    }

    @Test
    @DisplayName("正常短路：跳过后续 stage")
    void shortCircuitSkipsRemaining() {
        List<String> order = new ArrayList<>();

        PipelineStage s1 = new SimpleStage("A", PipelineStageType.GUARD, order);
        PipelineStage s2 = new ShortCircuitStage("B", PipelineStageType.PROMPT);
        PipelineStage s3 = new SimpleStage("C", PipelineStageType.LLM, order);

        PipelineExecutor executor = new PipelineExecutor(new PipelineStage[]{s1, s2, s3}, logger);
        executor.run(ctx);

        assertEquals(List.of("A"), order);
        assertTrue(ctx.shortCircuit);
        assertEquals(PipelineStageType.PROMPT, ctx.failedStage);
    }

    @Test
    @DisplayName("StageException 真异常：记录 metrics + 设置 failedStage")
    void stageExceptionRecordsFailure() {
        List<String> order = new ArrayList<>();

        PipelineStage s1 = new SimpleStage("A", PipelineStageType.GUARD, order);
        PipelineStage s2 = new ExceptionStage("B", PipelineStageType.LLM, "LLM timeout");
        PipelineStage s3 = new SimpleStage("C", PipelineStageType.RESPONSE, order);

        PipelineExecutor executor = new PipelineExecutor(new PipelineStage[]{s1, s2, s3}, logger);
        executor.run(ctx);

        assertEquals(List.of("A"), order);
        assertTrue(ctx.shortCircuit);
        assertEquals(PipelineStageType.LLM, ctx.failedStage);
        assertNotNull(ctx.failureReason);
        assertTrue(ctx.failureReason.contains("LLM timeout"));
    }

    @Test
    @DisplayName("Stage latency 被记录到 context")
    void stageLatencyRecorded() {
        List<String> order = new ArrayList<>();
        PipelineStage s1 = new SimpleStage("A", PipelineStageType.GUARD, order, c -> {
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        });

        PipelineExecutor executor = new PipelineExecutor(new PipelineStage[]{s1}, logger);
        executor.run(ctx);

        assertNotNull(ctx.stageLatencyMs.get(PipelineStageType.GUARD));
        assertTrue(ctx.stageLatencyMs.get(PipelineStageType.GUARD) >= 4);
    }

    @Test
    @DisplayName("空 stages 数组不抛异常")
    void emptyStagesArray() {
        PipelineExecutor executor = new PipelineExecutor(new PipelineStage[]{}, logger);
        assertDoesNotThrow(() -> executor.run(ctx));
        assertNull(ctx.failedStage);
    }

    // ---- Helper stage implementations ----

    private static class SimpleStage implements PipelineStage {
        private final String name;
        private final PipelineStageType type;
        private final List<String> order;
        private final java.util.function.Consumer<PipelineContext> action;

        SimpleStage(String name, PipelineStageType type, List<String> order) {
            this(name, type, order, null);
        }

        SimpleStage(String name, PipelineStageType type, List<String> order, java.util.function.Consumer<PipelineContext> action) {
            this.name = name;
            this.type = type;
            this.order = order;
            this.action = action;
        }

        @Override public String name() { return name; }
        @Override public PipelineStageType type() { return type; }
        @Override public void execute(PipelineContext ctx) {
            order.add(name);
            if (action != null) action.accept(ctx);
        }
    }

    private static class ShortCircuitStage implements PipelineStage {
        private final String name;
        private final PipelineStageType type;

        ShortCircuitStage(String name, PipelineStageType type) {
            this.name = name;
            this.type = type;
        }

        @Override public String name() { return name; }
        @Override public PipelineStageType type() { return type; }
        @Override public void execute(PipelineContext ctx) {
            ctx.shortCircuit = true;
            ctx.failedStage = type;
            ctx.failureReason = "business reject";
        }
    }

    private static class ExceptionStage implements PipelineStage {
        private final String name;
        private final PipelineStageType type;
        private final String reason;

        ExceptionStage(String name, PipelineStageType type, String reason) {
            this.name = name;
            this.type = type;
            this.reason = reason;
        }

        @Override public String name() { return name; }
        @Override public PipelineStageType type() { return type; }
        @Override public void execute(PipelineContext ctx) {
            throw new StageException(type, reason);
        }
    }
}
