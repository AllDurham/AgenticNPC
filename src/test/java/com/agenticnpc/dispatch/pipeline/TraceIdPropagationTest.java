package com.agenticnpc.dispatch.pipeline;

import com.agenticnpc.console.MetricsCollector;
import com.agenticnpc.console.PromptSnapshotStore;
import com.agenticnpc.console.RecentInteractionStore;
import com.agenticnpc.model.ActionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceId 传播测试：验证 traceId 在各组件中正确传递。
 */
class TraceIdPropagationTest {

    @Test
    @DisplayName("InteractionRecord 包含 traceId")
    void interactionRecordHasTraceId() {
        RecentInteractionStore store = new RecentInteractionStore();
        store.add(new RecentInteractionStore.InteractionRecord(
            "abc123def456", System.currentTimeMillis(), "Steve", "brain1", "hello",
            ActionType.NONE, 100, 50, false
        ));

        var list = store.getRecent(1);
        assertEquals(1, list.size());
        assertEquals("abc123def456", list.get(0).traceId());
    }

    @Test
    @DisplayName("PromptSnapshot 包含 traceId")
    void promptSnapshotHasTraceId() {
        PromptSnapshotStore store = new PromptSnapshotStore();
        store.add(new PromptSnapshotStore.PromptSnapshot(
            "xyz789abc123", System.currentTimeMillis(), "Steve", "uuid", "brain1", "NPC",
            "system", "history", "input", "output", "NONE",
            100, 50, 150,
            50, 10, 20, 5, 15, 10, 40, 30, 8,
            "NONE",
            "current", 50
        ));

        var snap = store.getById(0);
        assertNotNull(snap);
        assertEquals("xyz789abc123", snap.traceId());
    }

    @Test
    @DisplayName("PipelineSnapshot 包含 traceId")
    void pipelineSnapshotHasTraceId() {
        MetricsCollector mc = new MetricsCollector();
        var stageMs = new java.util.EnumMap<PipelineStageType, Long>(PipelineStageType.class);
        stageMs.put(PipelineStageType.GUARD, 5L);
        stageMs.put(PipelineStageType.LLM, 500L);

        mc.addPipelineSnapshot(new MetricsCollector.PipelineSnapshot(
            "pipe-trace-001", 800L, stageMs, null
        ));

        var snapshots = mc.getRecentSnapshots(1);
        assertEquals(1, snapshots.size());
        assertEquals("pipe-trace-001", snapshots.get(0).traceId());
        assertNull(snapshots.get(0).failedStage());
    }

    @Test
    @DisplayName("PipelineSnapshot 记录 failedStage")
    void pipelineSnapshotRecordsFailedStage() {
        MetricsCollector mc = new MetricsCollector();
        var stageMs = new java.util.EnumMap<PipelineStageType, Long>(PipelineStageType.class);

        mc.addPipelineSnapshot(new MetricsCollector.PipelineSnapshot(
            "fail-trace", 200L, stageMs, "LLM"
        ));

        var snapshots = mc.getRecentSnapshots(1);
        assertEquals("LLM", snapshots.get(0).failedStage());
    }

    @Test
    @DisplayName("MetricsCollector stage latency 记录和查询")
    void stageLatencyRecording() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordStageLatency(PipelineStageType.GUARD, 10);
        mc.recordStageLatency(PipelineStageType.GUARD, 20);
        mc.recordStageLatency(PipelineStageType.LLM, 500);

        var snap = mc.snapshot();
        // GUARD avg = (10+20)/2 = 15.0
        assertEquals(15.0, snap.stageAvgLatencyMs().get("GUARD"), 0.01);
        assertEquals(500.0, snap.stageAvgLatencyMs().get("LLM"), 0.01);
        assertNull(snap.stageAvgLatencyMs().get("PROMPT"));
    }

    @Test
    @DisplayName("MetricsCollector stage failure 计数")
    void stageFailureCounting() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordStageFailure(PipelineStageType.LLM);
        mc.recordStageFailure(PipelineStageType.LLM);
        mc.recordStageFailure(PipelineStageType.GUARD);

        var snap = mc.snapshot();
        assertEquals(2L, snap.stageFailureCounts().get("LLM"));
        assertEquals(1L, snap.stageFailureCounts().get("GUARD"));
    }
}
