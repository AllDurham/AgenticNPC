package com.agenticnpc.dispatch.pipeline.stages;

import com.agenticnpc.audit.AuditLogger;
import com.agenticnpc.audit.TokenTracker;
import com.agenticnpc.dispatch.CircuitBreaker;
import com.agenticnpc.dispatch.LLMClient;
import com.agenticnpc.dispatch.pipeline.PipelineContext;
import com.agenticnpc.dispatch.pipeline.PipelineStage;
import com.agenticnpc.dispatch.pipeline.PipelineStageType;
import com.agenticnpc.dispatch.pipeline.StageException;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * LLMStage：熔断器 + LLM 调用 + Token 统计。
 *
 * 特殊要求（排障核心）：
 * - 记录 request start / success / timeout / failure
 * - 记录精确 latency
 * - 真异常抛 StageException
 */
public class LLMStage implements PipelineStage {

    private final CircuitBreaker circuitBreaker;
    private final LLMClient      llmClient;
    private final TokenTracker   tokenTracker;
    private final AuditLogger    auditLogger;
    private final Logger         logger;

    public LLMStage(CircuitBreaker circuitBreaker, LLMClient llmClient,
                     TokenTracker tokenTracker, AuditLogger auditLogger, Logger logger) {
        this.circuitBreaker = circuitBreaker;
        this.llmClient      = llmClient;
        this.tokenTracker   = tokenTracker;
        this.auditLogger    = auditLogger;
        this.logger         = logger;
    }

    @Override
    public String name() { return "LLMStage"; }

    @Override
    public PipelineStageType type() { return PipelineStageType.LLM; }

    @Override
    public void execute(PipelineContext ctx) {
        Player player = ctx.event.player();
        String brainId = ctx.event.npcBrainId();
        String traceId = ctx.traceId;

        logger.info(String.format("[%s][LLMStage] request start", traceId));

        long requestStartMs = System.currentTimeMillis();

        // 通过熔断器发起 LLM 请求（同步阻塞等待结果）
        LLMClient.LLMRawResult llmResult;
        try {
            llmResult = circuitBreaker.execute(
                () -> llmClient.sendAsync(ctx.promptPackage),
                () -> null
            ).get(); // 同步等待
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - requestStartMs;
            logger.warning(String.format("[%s][LLMStage] failure | latency=%dms | reason=%s",
                traceId, latencyMs, e.getMessage()));
            if (auditLogger != null) {
                auditLogger.logParseFailed(traceId, player.getUniqueId(), player.getName(), brainId, e.getMessage());
            }
            throw new StageException(type(), "LLM 请求异常: " + e.getMessage(), e);
        }

        long latencyMs = System.currentTimeMillis() - requestStartMs;

        if (llmResult == null) {
            // 熔断器打开，快速失败
            logger.warning(String.format("[%s][LLMStage] circuit open | latency=%dms", traceId, latencyMs));
            if (auditLogger != null) {
                auditLogger.logCircuitOpen(traceId, brainId, 0);
            }
            ctx.shortCircuit = true;
            ctx.failedStage = type();
            ctx.failureReason = "熔断器打开";
            return;
        }

        logger.info(String.format("[%s][LLMStage] success | latency=%dms", traceId, latencyMs));

        // Token 统计
        if (tokenTracker != null) {
            tokenTracker.track(llmResult.fullResponseBody(),
                player.getUniqueId(), player.getName(), brainId);
        }

        // 输出到 context
        ctx.llmRawContent = llmResult.contentText();
        ctx.llmFullResponseBody = llmResult.fullResponseBody();
    }
}
