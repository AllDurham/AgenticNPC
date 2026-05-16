package com.agenticnpc.dispatch.pipeline;

import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.gateway.LLMResponseParser;
import com.agenticnpc.gateway.model.ItemSafetyResult;
import com.agenticnpc.gateway.model.ValidationResult;
import com.agenticnpc.model.InteractionEvent;
import com.agenticnpc.model.LLMResponse;
import com.agenticnpc.model.PromptPackage;
import com.agenticnpc.model.PromptTokenBreakdown;

import java.util.EnumMap;

/**
 * Pipeline 上下文，在 stage 之间传递数据。
 * 轻量级，不变成 ServiceLocator 或全局 mutable bag。
 */
public class PipelineContext {

    // ---- Identity（只读）----
    public final String            traceId;
    public final InteractionEvent  event;
    public final BrainConfig       brain;

    // ---- Stage outputs（stage 间传递）----
    public PromptPackage    promptPackage;     // GuardStage → PromptStage → LLMStage
    public String           llmRawContent;     // LLMStage → ResponseStage
    public String           llmFullResponseBody; // LLMStage → ResponseStage（Token 统计用）
    public LLMResponse      response;          // ResponseStage → ExecutionStage
    public ValidationResult validation;        // ResponseStage → ExecutionStage
    public boolean          parseFallback;     // ResponseStage → ExecutionStage（metrics 用）
    public PromptTokenBreakdown breakdown;     // PromptStage → ExecutionStage（token 分段统计）
    public String                variant;      // PromptStage → ExecutionStage（prompt variant 标识）
    public LLMResponseParser.FallbackType fallbackType; // ResponseStage → ExecutionStage（fallback 类型）
    public ItemSafetyResult    itemSafetyResult; // ResponseStage → Dispatcher（物品安全检查结果）

    // ---- Timing ----
    public long pipelineStartMs;
    public final EnumMap<PipelineStageType, Long> stageLatencyMs = new EnumMap<>(PipelineStageType.class);

    // ---- Failure tracking ----
    public PipelineStageType failedStage;      // null = success
    public boolean           shortCircuit;      // true = 不继续执行后续 stage
    public String            failureReason;     // 短路原因（供日志/metrics 使用）

    public PipelineContext(String traceId, InteractionEvent event, BrainConfig brain) {
        this.traceId = traceId;
        this.event   = event;
        this.brain   = brain;
    }
}
