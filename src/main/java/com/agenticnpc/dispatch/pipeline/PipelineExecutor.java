package com.agenticnpc.dispatch.pipeline;

import java.util.logging.Logger;

/**
 * Pipeline 阶段执行器。
 * 线性执行 stage，支持 short-circuit 和异常捕获。
 */
public class PipelineExecutor {

    private final PipelineStage[] stages;
    private final Logger          logger;

    public PipelineExecutor(PipelineStage[] stages, Logger logger) {
        this.stages = stages;
        this.logger = logger;
    }

    /**
     * 线性执行所有 stage。
     *
     * 行为：
     * - 正常短路（ctx.shortCircuit = true）：跳过后续 stage，记录 metrics
     * - 真异常（StageException）：记录 error 日志 + metrics，设置 ctx.failedStage
     */
    public void run(PipelineContext ctx) {
        for (PipelineStage stage : stages) {
            // 检查是否已被短路
            if (ctx.shortCircuit) break;

            long startMs = System.currentTimeMillis();
            try {
                stage.execute(ctx);
            } catch (StageException e) {
                // 真异常
                ctx.failedStage = e.stageType() != null ? e.stageType() : stage.type();
                ctx.failureReason = e.getMessage();
                ctx.shortCircuit = true;
                logger.warning(String.format(
                    "[%s][%s] Stage 异常: %s",
                    ctx.traceId, stage.name(), e.getMessage()
                ));
            }
            long elapsed = System.currentTimeMillis() - startMs;
            ctx.stageLatencyMs.put(stage.type(), elapsed);
        }
    }
}
