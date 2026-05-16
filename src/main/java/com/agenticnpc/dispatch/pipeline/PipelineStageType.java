package com.agenticnpc.dispatch.pipeline;

/**
 * Pipeline Stage 类型枚举。
 * 使用 enum 而非裸 ordinal，防止顺序变更时爆炸。
 */
public enum PipelineStageType {
    GUARD,
    PROMPT,
    LLM,
    RESPONSE,
    EXECUTION
}
