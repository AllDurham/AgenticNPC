package com.agenticnpc.console;

import com.agenticnpc.dispatch.pipeline.PipelineStageType;
import com.agenticnpc.model.ActionType;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 统一指标采集器。
 *
 * 由 AsyncDispatcher 在管线各步骤调用，WebConsole 读取快照。
 * 全部使用无锁原子操作，零额外线程。
 *
 * 指标列表：
 * - request_count:        总请求计数
 * - request_failures:     失败计数（LLM 异常 / 熔断 / 解析失败）
 * - parse_fallbacks:      JSON 解析回退到纯文本兜底
 * - semantic_blocks:      SemanticGuard BLOCKED 计数
 * - action_counts:        按 ActionType 分类计数
 * - prompt_tokens:        累计 prompt token 数
 * - completion_tokens:    累计 completion token 数
 * - total_tokens:         累计总 token 数
 * - latency_sum_ms:       延迟累加（用于计算平均值）
 * - latency_max_ms:       最大延迟
 * - latency_count:        延迟样本计数
 */
public class MetricsCollector {

    private final LongAdder requestCount     = new LongAdder();
    private final LongAdder requestFailures  = new LongAdder();
    private final LongAdder parseFallbacks   = new LongAdder();
    private final LongAdder semanticBlocks   = new LongAdder();

    private final LongAdder promptTokens     = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();
    private final LongAdder totalTokens      = new LongAdder();

    private final LongAdder   latencySum = new LongAdder();
    private final LongAdder   latencyCount = new LongAdder();
    private final AtomicLong  latencyMax   = new AtomicLong(0);

    // v1.4-b: Runtime metrics
    private final AtomicLong  queueDepth     = new AtomicLong(0);
    private final AtomicLong  inflightRequests = new AtomicLong(0);
    private final LongAdder   llmLatencySum  = new LongAdder();
    private final LongAdder   llmLatencyCount = new LongAdder();
    private final LongAdder   llmTimeoutCount = new LongAdder();
    private final LongAdder   summaryCompressionCount = new LongAdder();
    private final LongAdder   memoryCacheHits = new LongAdder();
    private final LongAdder   memoryCacheMisses = new LongAdder();

    // Parser fallback 分类计数
    private final ConcurrentHashMap<String, LongAdder> fallbackTypeCounts = new ConcurrentHashMap<>();

    // 按 ActionType 分类计数
    private final ConcurrentHashMap<ActionType, LongAdder> actionCounts = new ConcurrentHashMap<>();

    // Pipeline stage latency（按 stage enum 累加，避免 typo）
    private final ConcurrentHashMap<PipelineStageType, LongAdder> stageLatencySum   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PipelineStageType, LongAdder> stageLatencyCount = new ConcurrentHashMap<>();
    private final AtomicLong pipelineLatencyMax = new AtomicLong(0);

    // Stage failure counts（按 stage enum 计数）
    private final ConcurrentHashMap<PipelineStageType, LongAdder> stageFailureCounts = new ConcurrentHashMap<>();

    // v1.4-c: Per-variant metrics
    private final ConcurrentHashMap<String, LongAdder> variantTokenTotals      = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> variantRequestCounts     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> variantFallbackCounts    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> variantLatencyTotals     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> variantActionSuccessCounts = new ConcurrentHashMap<>();

    // Pipeline Snapshot ring buffer（最近 100 条完整 pipeline 记录）
    private static final int SNAPSHOT_CAPACITY = 100;
    private final PipelineSnapshot[] snapshotRing = new PipelineSnapshot[SNAPSHOT_CAPACITY];
    private volatile int snapshotHead = 0;
    private volatile int snapshotSize = 0;

    // 最近1分钟窗口（滑动窗口：维护每秒的计数桶）
    private static final int WINDOW_SECONDS = 60;
    private final AtomicLong[] requestBuckets  = new AtomicLong[WINDOW_SECONDS];
    private final AtomicLong[] failureBuckets   = new AtomicLong[WINDOW_SECONDS];
    private final AtomicLong[] tokenBuckets     = new AtomicLong[WINDOW_SECONDS];
    private volatile int currentBucketIndex = 0;
    private volatile long lastBucketTimeMs  = System.currentTimeMillis();

    public MetricsCollector() {
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            requestBuckets[i]  = new AtomicLong(0);
            failureBuckets[i]   = new AtomicLong(0);
            tokenBuckets[i]     = new AtomicLong(0);
        }
    }

    // ---- 记录方法（由 AsyncDispatcher 调用）----

    /** 记录一次成功请求 */
    public void recordSuccess(ActionType actionType, long latencyMs,
                              int promptToken, int completionToken, int totalToken,
                              boolean isFallback) {
        requestCount.increment();
        promptTokens.add(promptToken);
        completionTokens.add(completionToken);
        totalTokens.add(totalToken);
        recordLatency(latencyMs);

        if (actionType != null) {
            actionCounts.computeIfAbsent(actionType, k -> new LongAdder()).increment();
        }

        if (isFallback) {
            parseFallbacks.increment();
        }

        advanceBucket();
        requestBuckets[currentBucketIndex].incrementAndGet();
        tokenBuckets[currentBucketIndex].addAndGet(totalToken);
    }

    /** 记录一次失败（LLM 异常、熔断、解析失败等） */
    public void recordFailure() {
        requestFailures.increment();
        advanceBucket();
        failureBuckets[currentBucketIndex].incrementAndGet();
    }

    /** 记录 SemanticGuard 拦截 */
    public void recordSemanticBlock() {
        semanticBlocks.increment();
    }

    // ---- v1.4-b: Runtime metrics ----

    /** 更新队列深度 */
    public void setQueueDepth(long depth) { queueDepth.set(depth); }

    /** 进入 pipeline */
    public void recordInflightStart() { inflightRequests.incrementAndGet(); }

    /** 离开 pipeline */
    public void recordInflightEnd() { inflightRequests.decrementAndGet(); }

    /** 记录 LLM 延迟 */
    public void recordLLMLatency(long ms) {
        llmLatencySum.add(ms);
        llmLatencyCount.increment();
    }

    /** 记录 LLM 超时 */
    public void recordLLMTimeout() { llmTimeoutCount.increment(); }

    /** 记录 parser fallback（按类型分类） */
    public void recordFallbackType(String fallbackType) {
        parseFallbacks.increment();
        fallbackTypeCounts.computeIfAbsent(fallbackType, k -> new LongAdder()).increment();
    }

    /** v1.4-c: 记录单次请求的 variant 维度指标 */
    public void recordVariantMetrics(String variant, int totalTokens, long latencyMs,
                                     boolean isFallback, boolean hasValidAction) {
        String v = variant != null ? variant : "current";
        variantTokenTotals.computeIfAbsent(v, k -> new LongAdder()).add(totalTokens);
        variantRequestCounts.computeIfAbsent(v, k -> new LongAdder()).increment();
        variantLatencyTotals.computeIfAbsent(v, k -> new LongAdder()).add(latencyMs);
        if (isFallback) {
            variantFallbackCounts.computeIfAbsent(v, k -> new LongAdder()).increment();
        }
        if (hasValidAction) {
            variantActionSuccessCounts.computeIfAbsent(v, k -> new LongAdder()).increment();
        }
    }

    /** 记录摘要压缩 */
    public void recordSummaryCompression() { summaryCompressionCount.increment(); }

    /** 记忆缓存命中 */
    public void recordMemoryCacheHit() { memoryCacheHits.increment(); }

    /** 记忆缓存未命中 */
    public void recordMemoryCacheMiss() { memoryCacheMisses.increment(); }

    /** 记录单个 stage 延迟 */
    public void recordStageLatency(PipelineStageType stage, long ms) {
        stageLatencySum.computeIfAbsent(stage, k -> new LongAdder()).add(ms);
        stageLatencyCount.computeIfAbsent(stage, k -> new LongAdder()).increment();
    }

    /** 记录 pipeline 总延迟 */
    public void recordPipelineLatency(long ms) {
        pipelineLatencyMax.updateAndGet(prev -> Math.max(prev, ms));
    }

    /** 记录 stage 失败 */
    public void recordStageFailure(PipelineStageType stage) {
        stageFailureCounts.computeIfAbsent(stage, k -> new LongAdder()).increment();
    }

    /** 保存一条 Pipeline Snapshot（ring buffer） */
    public synchronized void addPipelineSnapshot(PipelineSnapshot snapshot) {
        snapshotRing[snapshotHead] = snapshot;
        snapshotHead = (snapshotHead + 1) % SNAPSHOT_CAPACITY;
        if (snapshotSize < SNAPSHOT_CAPACITY) snapshotSize++;
    }

    /** 获取最近的 Pipeline Snapshots */
    public synchronized java.util.List<PipelineSnapshot> getRecentSnapshots(int maxCount) {
        if (snapshotSize == 0) return java.util.Collections.emptyList();
        int count = Math.min(maxCount, snapshotSize);
        java.util.List<PipelineSnapshot> result = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = (snapshotHead - 1 - i + SNAPSHOT_CAPACITY) % SNAPSHOT_CAPACITY;
            result.add(snapshotRing[index]);
        }
        return result;
    }

    // ---- 查询方法（由 WebConsole 读取）----

    public MetricsSnapshot snapshot() {
        long now = advanceBucket();

        // 最近1分钟统计
        long recentRequests = 0;
        long recentFailures = 0;
        long recentTokens   = 0;
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            recentRequests += requestBuckets[i].get();
            recentFailures += failureBuckets[i].get();
            recentTokens   += tokenBuckets[i].get();
        }

        long count = latencyCount.sum();
        double avgLatency = count > 0 ? (double) latencySum.sum() / count : 0;

        Map<String, Long> actionMap = new ConcurrentHashMap<>();
        actionCounts.forEach((k, v) -> actionMap.put(k.name(), v.sum()));

        return buildSnapshot(recentRequests, recentFailures, recentTokens, avgLatency, actionMap);
    }

    // ---- 内部 ----

    private void recordLatency(long ms) {
        latencySum.add(ms);
        latencyCount.increment();
        latencyMax.updateAndGet(prev -> Math.max(prev, ms));
    }

    /**
     * 推进滑动窗口。
     * 如果距离上次推进超过1秒，清除过期桶。
     */
    private synchronized long advanceBucket() {
        long now = System.currentTimeMillis();
        long elapsedSeconds = (now - lastBucketTimeMs) / 1000;
        if (elapsedSeconds <= 0) return now;

        // 清除过期桶（最多清 WINDOW_SECONDS 个）
        int steps = (int) Math.min(elapsedSeconds, WINDOW_SECONDS);
        for (int i = 0; i < steps; i++) {
            currentBucketIndex = (currentBucketIndex + 1) % WINDOW_SECONDS;
            requestBuckets[currentBucketIndex].set(0);
            failureBuckets[currentBucketIndex].set(0);
            tokenBuckets[currentBucketIndex].set(0);
        }
        lastBucketTimeMs = now;
        return now;
    }

    /**
     * 指标快照（不可变）。
     */
    public record MetricsSnapshot(
        long   totalRequests,
        long   totalFailures,
        long   parseFallbacks,
        long   semanticBlocks,
        long   promptTokensTotal,
        long   completionTokensTotal,
        long   totalTokensTotal,
        double avgLatencyMs,
        long   maxLatencyMs,
        long   recentMinuteRequests,
        long   recentMinuteFailures,
        long   recentMinuteTokens,
        Map<String, Long> actionCounts,
        // Pipeline metrics
        Map<String, Double> stageAvgLatencyMs,
        long                pipelineLatencyMax,
        Map<String, Long>   stageFailureCounts,
        // v1.4-b: Runtime metrics
        long   queueDepth,
        long   inflightRequests,
        double avgLLMLatencyMs,
        long   llmTimeoutCount,
        long   summaryCompressionCount,
        double memoryCacheHitRate,
        Map<String, Long> fallbackTypeCounts,
        // v1.4-c: Per-variant metrics
        Map<String, Map<String, Object>> variantMetrics
    ) {}

    /**
     * 单次 Pipeline 执行快照（ring buffer 存储）。
     */
    public record PipelineSnapshot(
        String                    traceId,
        long                      totalMs,
        EnumMap<PipelineStageType, Long> stageMs,
        String                    failedStage
    ) {}

    /** 构建包含 stage latency 的 MetricsSnapshot */
    private MetricsSnapshot buildSnapshot(long recentRequests, long recentFailures, long recentTokens,
                                          double avgLatency, Map<String, Long> actionMap) {
        // Stage avg latency
        Map<String, Double> stageAvgMap = new ConcurrentHashMap<>();
        stageLatencySum.forEach((stage, sum) -> {
            LongAdder count = stageLatencyCount.get(stage);
            double avg = count != null && count.sum() > 0 ? (double) sum.sum() / count.sum() : 0;
            stageAvgMap.put(stage.name(), avg);
        });

        // Stage failure counts
        Map<String, Long> stageFailMap = new ConcurrentHashMap<>();
        stageFailureCounts.forEach((stage, count) -> stageFailMap.put(stage.name(), count.sum()));

        // v1.4-b: Runtime metrics
        long llmCount = llmLatencyCount.sum();
        double avgLLMLatency = llmCount > 0 ? (double) llmLatencySum.sum() / llmCount : 0;
        long cacheTotal = memoryCacheHits.sum() + memoryCacheMisses.sum();
        double cacheHitRate = cacheTotal > 0 ? (double) memoryCacheHits.sum() / cacheTotal : 0;

        Map<String, Long> fallbackMap = new ConcurrentHashMap<>();
        fallbackTypeCounts.forEach((k, v) -> fallbackMap.put(k, v.sum()));

        // v1.4-c: Per-variant metrics
        Map<String, Map<String, Object>> variantMetricsMap = new ConcurrentHashMap<>();
        variantRequestCounts.forEach((variant, count) -> {
            long reqCount = count.sum();
            long tokenTotal = variantTokenTotals.containsKey(variant) ? variantTokenTotals.get(variant).sum() : 0;
            long fallbackCount = variantFallbackCounts.containsKey(variant) ? variantFallbackCounts.get(variant).sum() : 0;
            long latencyTotal = variantLatencyTotals.containsKey(variant) ? variantLatencyTotals.get(variant).sum() : 0;
            long actionCount = variantActionSuccessCounts.containsKey(variant) ? variantActionSuccessCounts.get(variant).sum() : 0;

            Map<String, Object> metrics = new ConcurrentHashMap<>();
            metrics.put("requestCount", reqCount);
            metrics.put("avgTokens", reqCount > 0 ? (double) tokenTotal / reqCount : 0.0);
            metrics.put("fallbackRate", reqCount > 0 ? (double) fallbackCount / reqCount : 0.0);
            metrics.put("avgLatencyMs", reqCount > 0 ? (double) latencyTotal / reqCount : 0.0);
            metrics.put("actionSuccessRate", reqCount > 0 ? (double) actionCount / reqCount : 0.0);
            metrics.put("totalTokens", tokenTotal);
            metrics.put("fallbackCount", fallbackCount);
            variantMetricsMap.put(variant, metrics);
        });

        return new MetricsSnapshot(
            requestCount.sum(), requestFailures.sum(), parseFallbacks.sum(), semanticBlocks.sum(),
            promptTokens.sum(), completionTokens.sum(), totalTokens.sum(),
            avgLatency, latencyMax.get(),
            recentRequests, recentFailures, recentTokens,
            actionMap, stageAvgMap, pipelineLatencyMax.get(), stageFailMap,
            queueDepth.get(), inflightRequests.get(), avgLLMLatency,
            llmTimeoutCount.sum(), summaryCompressionCount.sum(), cacheHitRate,
            fallbackMap, variantMetricsMap
        );
    }
}
