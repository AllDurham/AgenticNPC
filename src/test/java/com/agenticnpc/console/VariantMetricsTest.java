package com.agenticnpc.console;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariantMetricsTest {

    @Test
    @DisplayName("recordVariantMetrics 累加 per-variant 统计")
    void recordVariantMetricsAccumulates() {
        MetricsCollector mc = new MetricsCollector();
        mc.recordVariantMetrics("current", 100, 200, false, false);
        mc.recordVariantMetrics("current", 150, 300, false, true);
        mc.recordVariantMetrics("slim-v1", 80, 150, true, false);

        var snap = mc.snapshot();
        Map<String, Map<String, Object>> vm = snap.variantMetrics();

        assertNotNull(vm);
        assertEquals(2, vm.size());

        Map<String, Object> currentMetrics = vm.get("current");
        assertEquals(2L, currentMetrics.get("requestCount"));
        assertEquals(125.0, (Double) currentMetrics.get("avgTokens"), 0.01);
        assertEquals(0.0, (Double) currentMetrics.get("fallbackRate"), 0.01);
        assertEquals(250.0, (Double) currentMetrics.get("avgLatencyMs"), 0.01);
        assertEquals(0.5, (Double) currentMetrics.get("actionSuccessRate"), 0.01);

        Map<String, Object> slimMetrics = vm.get("slim-v1");
        assertEquals(1L, slimMetrics.get("requestCount"));
        assertEquals(1.0, (Double) slimMetrics.get("fallbackRate"), 0.01);
        assertEquals(0.0, (Double) slimMetrics.get("actionSuccessRate"), 0.01);
    }

    @Test
    @DisplayName("null variant 默认归入 current")
    void nullVariantDefaultsToCurrent() {
        MetricsCollector mc = new MetricsCollector();
        mc.recordVariantMetrics(null, 100, 200, false, false);

        var vm = mc.snapshot().variantMetrics();
        assertTrue(vm.containsKey("current"));
    }

    @Test
    @DisplayName("无 variant 记录时返回空 map")
    void emptyVariantMetrics() {
        MetricsCollector mc = new MetricsCollector();
        var vm = mc.snapshot().variantMetrics();
        assertNotNull(vm);
        assertTrue(vm.isEmpty());
    }

    @Test
    @DisplayName("actionSuccessRate = hasValidAction / requestCount")
    void actionSuccessRateCorrect() {
        MetricsCollector mc = new MetricsCollector();
        // 4 requests, 1 has valid action
        mc.recordVariantMetrics("v1", 100, 100, false, true);
        mc.recordVariantMetrics("v1", 100, 100, false, false);
        mc.recordVariantMetrics("v1", 100, 100, false, false);
        mc.recordVariantMetrics("v1", 100, 100, true, false);

        var vm = mc.snapshot().variantMetrics();
        Map<String, Object> v1 = vm.get("v1");
        assertEquals(4L, v1.get("requestCount"));
        assertEquals(0.25, (Double) v1.get("actionSuccessRate"), 0.01);
        assertEquals(0.25, (Double) v1.get("fallbackRate"), 0.01);
    }
}
