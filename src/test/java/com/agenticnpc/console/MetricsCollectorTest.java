package com.agenticnpc.console;

import com.agenticnpc.model.ActionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MetricsCollectorTest {

    @Test
    @DisplayName("初始状态：所有计数为零")
    void initialStateAllZeros() {
        MetricsCollector mc = new MetricsCollector();
        MetricsCollector.MetricsSnapshot snap = mc.snapshot();

        assertEquals(0, snap.totalRequests());
        assertEquals(0, snap.totalFailures());
        assertEquals(0, snap.parseFallbacks());
        assertEquals(0, snap.semanticBlocks());
        assertEquals(0, snap.promptTokensTotal());
        assertEquals(0, snap.totalTokensTotal());
        assertEquals(0, snap.maxLatencyMs());
        assertEquals(0.0, snap.avgLatencyMs(), 0.01);
    }

    @Test
    @DisplayName("recordSuccess 累加各项指标")
    void recordSuccessAccumulates() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordSuccess(ActionType.GIVE_ITEM, 100, 50, 30, 80, false);
        mc.recordSuccess(ActionType.NONE, 200, 100, 60, 160, false);

        MetricsCollector.MetricsSnapshot snap = mc.snapshot();

        assertEquals(2, snap.totalRequests());
        assertEquals(150, snap.promptTokensTotal());
        assertEquals(90, snap.completionTokensTotal());
        assertEquals(240, snap.totalTokensTotal());
        assertEquals(200, snap.maxLatencyMs());
        assertTrue(snap.avgLatencyMs() > 0);
    }

    @Test
    @DisplayName("recordSuccess 按 ActionType 分类计数")
    void actionCountsByType() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordSuccess(ActionType.GIVE_ITEM, 50, 10, 10, 20, false);
        mc.recordSuccess(ActionType.GIVE_ITEM, 50, 10, 10, 20, false);
        mc.recordSuccess(ActionType.TELEPORT, 50, 10, 10, 20, false);

        MetricsCollector.MetricsSnapshot snap = mc.snapshot();

        assertEquals(2L, snap.actionCounts().get("GIVE_ITEM"));
        assertEquals(1L, snap.actionCounts().get("TELEPORT"));
    }

    @Test
    @DisplayName("recordSuccess fallback 累加")
    void fallbackCounter() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordSuccess(ActionType.NONE, 50, 10, 10, 20, true);
        mc.recordSuccess(ActionType.NONE, 50, 10, 10, 20, false);

        assertEquals(1, mc.snapshot().parseFallbacks());
    }

    @Test
    @DisplayName("recordFailure 累加失败计数")
    void failureCounter() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordFailure();
        mc.recordFailure();

        assertEquals(2, mc.snapshot().totalFailures());
    }

    @Test
    @DisplayName("recordSemanticBlock 累加语义拦截计数")
    void semanticBlockCounter() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordSemanticBlock();

        assertEquals(1, mc.snapshot().semanticBlocks());
    }

    @Test
    @DisplayName("maxLatencyMs 追踪最大延迟")
    void maxLatencyTracking() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordSuccess(ActionType.NONE, 50, 10, 10, 20, false);
        mc.recordSuccess(ActionType.NONE, 500, 10, 10, 20, false);
        mc.recordSuccess(ActionType.NONE, 100, 10, 10, 20, false);

        assertEquals(500, mc.snapshot().maxLatencyMs());
    }

    @Test
    @DisplayName("avgLatencyMs 计算平均延迟")
    void avgLatencyCalculation() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordSuccess(ActionType.NONE, 100, 10, 10, 20, false);
        mc.recordSuccess(ActionType.NONE, 300, 10, 10, 20, false);

        assertEquals(200.0, mc.snapshot().avgLatencyMs(), 0.01);
    }

    @Test
    @DisplayName("recentMinute 计数器")
    void recentMinuteCounters() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordSuccess(ActionType.NONE, 50, 10, 10, 20, false);
        mc.recordSuccess(ActionType.NONE, 50, 10, 10, 30, false);
        mc.recordFailure();

        MetricsCollector.MetricsSnapshot snap = mc.snapshot();

        assertTrue(snap.recentMinuteRequests() >= 2);
        assertTrue(snap.recentMinuteFailures() >= 1);
        assertTrue(snap.recentMinuteTokens() >= 50);
    }

    @Test
    @DisplayName("snapshot 返回不可变快照（多次调用独立）")
    void snapshotIsolation() {
        MetricsCollector mc = new MetricsCollector();

        mc.recordSuccess(ActionType.NONE, 50, 10, 10, 20, false);
        MetricsCollector.MetricsSnapshot snap1 = mc.snapshot();

        mc.recordSuccess(ActionType.NONE, 50, 10, 10, 20, false);
        MetricsCollector.MetricsSnapshot snap2 = mc.snapshot();

        assertEquals(1, snap1.totalRequests());
        assertEquals(2, snap2.totalRequests());
    }
}
