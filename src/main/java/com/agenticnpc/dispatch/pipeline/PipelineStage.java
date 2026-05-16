package com.agenticnpc.dispatch.pipeline;

/**
 * Pipeline Stage 接口。
 * 普通 interface，不是 FunctionalInterface。
 * Stage 是有名字的命名组件，不是 lambda。
 */
public interface PipelineStage {

    /** Stage 名称（用于日志和 metrics） */
    String name();

    /** Stage 类型（用于 metrics 累加） */
    PipelineStageType type();

    /**
     * 执行 stage 逻辑。
     *
     * 正常业务拒绝（限流/审核/校验）：设置 ctx.shortCircuit = true，不抛异常。
     * 真异常（LLM 超时/解析崩溃）：抛 StageException。
     */
    void execute(PipelineContext ctx) throws StageException;
}
