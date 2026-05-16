package com.agenticnpc.dispatch.pipeline.stages;

import com.agenticnpc.audit.AuditLogger;
import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.dispatch.RateLimiter;
import com.agenticnpc.dispatch.pipeline.PipelineContext;
import com.agenticnpc.dispatch.pipeline.PipelineStage;
import com.agenticnpc.dispatch.pipeline.PipelineStageType;
import com.agenticnpc.dispatch.pipeline.StageException;
import com.agenticnpc.gateway.SemanticGuard;
import com.agenticnpc.model.InteractionEvent;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * GuardStage：限流检查 + SemanticGuard 语义审核。
 * 正常业务拒绝通过 ctx.shortCircuit 处理，不抛异常。
 */
public class GuardStage implements PipelineStage {

    private final RateLimiter   rateLimiter;
    private final SemanticGuard semanticGuard;
    private final AuditLogger   auditLogger;
    private final Logger        logger;

    public GuardStage(RateLimiter rateLimiter, SemanticGuard semanticGuard,
                      AuditLogger auditLogger, Logger logger) {
        this.rateLimiter   = rateLimiter;
        this.semanticGuard = semanticGuard;
        this.auditLogger   = auditLogger;
        this.logger        = logger;
    }

    @Override
    public String name() { return "GuardStage"; }

    @Override
    public PipelineStageType type() { return PipelineStageType.GUARD; }

    @Override
    public void execute(PipelineContext ctx) {
        InteractionEvent event = ctx.event;
        Player player = event.player();
        BrainConfig brain = ctx.brain;

        // ---- 限流检查 ----
        var limitResult = rateLimiter.tryAcquire(player.getUniqueId(), event.npcBrainId());
        if (!limitResult.allowed()) {
            if (auditLogger != null) {
                auditLogger.logRateLimited(event.traceId(), player.getUniqueId(), player.getName(), event.npcBrainId());
            }
            ctx.shortCircuit = true;
            ctx.failedStage = type();
            ctx.failureReason = limitResult.rejectMessage();
            logger.warning(String.format("[%s][GuardStage] 限流拒绝 | 玩家: %s", event.traceId(), player.getName()));
            return;
        }

        // ---- 语义注入防御 ----
        if (semanticGuard != null) {
            var verdict = semanticGuard.check(event.sanitizedInput());
            switch (verdict) {
                case BLOCKED -> {
                    logger.warning(String.format("[%s][GuardStage] SemanticGuard BLOCKED | 玩家: %s", event.traceId(), player.getName()));
                    if (auditLogger != null) {
                        auditLogger.logSemanticGuardBlocked(event.traceId(), player.getUniqueId(), player.getName(), event.npcBrainId(), event.sanitizedInput());
                    }
                    ctx.shortCircuit = true;
                    ctx.failedStage = type();
                    ctx.failureReason = "SemanticGuard BLOCKED";
                    return;
                }
                case SUSPICIOUS -> {
                    logger.warning(String.format("[%s][GuardStage] SemanticGuard SUSPICIOUS | 玩家: %s", event.traceId(), player.getName()));
                    if (auditLogger != null) {
                        auditLogger.logSemanticGuardSuspicious(event.traceId(), player.getUniqueId(), player.getName(), event.npcBrainId(), event.sanitizedInput());
                    }
                }
                case SAFE -> { /* 正常继续 */ }
            }
        }
    }
}
