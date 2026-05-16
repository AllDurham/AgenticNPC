package com.agenticnpc.dispatch.pipeline;

/**
 * Stage 真异常（LLM timeout / parser crash / SQL failure 等）。
 * RuntimeException，避免 throws 污染 stage 签名。
 *
 * 注意：正常业务拒绝（限流/审核/校验失败）不使用此异常，
 * 而是通过 ctx.shortCircuit = true 处理。
 */
public class StageException extends RuntimeException {

    private final PipelineStageType stageType;

    public StageException(PipelineStageType stageType, String reason) {
        super(reason);
        this.stageType = stageType;
    }

    public StageException(PipelineStageType stageType, String reason, Throwable cause) {
        super(reason, cause);
        this.stageType = stageType;
    }

    public PipelineStageType stageType() {
        return stageType;
    }
}
