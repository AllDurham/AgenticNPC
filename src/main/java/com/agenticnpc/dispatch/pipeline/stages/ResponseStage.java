package com.agenticnpc.dispatch.pipeline.stages;

import com.agenticnpc.audit.AuditLogger;
import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.console.MetricsCollector;
import com.agenticnpc.dispatch.pipeline.PipelineContext;
import com.agenticnpc.dispatch.pipeline.PipelineStage;
import com.agenticnpc.dispatch.pipeline.PipelineStageType;
import com.agenticnpc.dispatch.pipeline.StageException;
import com.agenticnpc.gateway.ActionValidator;
import com.agenticnpc.gateway.ItemSafetyGuard;
import com.agenticnpc.gateway.LLMResponseParser;
import com.agenticnpc.gateway.model.ItemSafetyResult;
import com.agenticnpc.gateway.model.ValidationResult;
import com.agenticnpc.model.ActionType;
import com.agenticnpc.model.LLMResponse;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * ResponseStage：LLM 响应解析 + 动作校验 + 物品安全检查。
 * 合并了原 ParseStage + ValidateStage + SafetyCheckStage。
 */
public class ResponseStage implements PipelineStage {

    private final LLMResponseParser responseParser;
    private final ActionValidator   actionValidator;
    private final ItemSafetyGuard   itemSafetyGuard;
    private final AuditLogger       auditLogger;
    private final MetricsCollector  metricsCollector;
    private final Logger            logger;

    public ResponseStage(LLMResponseParser responseParser, ActionValidator actionValidator,
                         ItemSafetyGuard itemSafetyGuard, AuditLogger auditLogger,
                         MetricsCollector metricsCollector, Logger logger) {
        this.responseParser  = responseParser;
        this.actionValidator = actionValidator;
        this.itemSafetyGuard = itemSafetyGuard;
        this.auditLogger     = auditLogger;
        this.metricsCollector = metricsCollector;
        this.logger          = logger;
    }

    @Override
    public String name() { return "ResponseStage"; }

    @Override
    public PipelineStageType type() { return PipelineStageType.RESPONSE; }

    @Override
    public void execute(PipelineContext ctx) {
        Player player = ctx.event.player();
        String brainId = ctx.event.npcBrainId();
        String traceId = ctx.traceId;
        BrainConfig brain = ctx.brain;

        // ---- 解析 LLM 响应（ParseResult 包含 response + fallbackType，无共享状态）----
        LLMResponseParser.ParseResult parseResult = responseParser.parse(ctx.llmRawContent);
        if (!parseResult.isPresent()) {
            logger.warning(String.format("[%s][ResponseStage] 解析失败", traceId));
            if (auditLogger != null) {
                auditLogger.logParseFailed(traceId, player.getUniqueId(), player.getName(), brainId, ctx.llmRawContent);
            }
            if (metricsCollector != null) metricsCollector.recordFailure();
            ctx.shortCircuit = true;
            ctx.failedStage = type();
            ctx.failureReason = "LLM 响应解析失败";
            return;
        }

        ctx.response = parseResult.get();
        ctx.fallbackType = parseResult.fallbackType();
        ctx.parseFallback = parseResult.isFallback();

        // ---- 动作校验 ----
        ValidationResult validation = actionValidator.validate(ctx.response, brainId);
        if (!validation.valid()) {
            logger.warning(String.format("[%s][ResponseStage] 动作校验失败: %s", traceId, validation.failReason()));
            if (auditLogger != null) {
                auditLogger.logActionBlocked(traceId, player.getUniqueId(), player.getName(), brainId,
                    validation.failReason(), validation.actionType());
            }
            ctx.shortCircuit = true;
            ctx.failedStage = type();
            ctx.failureReason = "动作校验失败: " + validation.failReason();
            return;
        }
        ctx.validation = validation;

        // ---- 物品安全检查（仅 GIVE_ITEM）----
        if (validation.actionType() == ActionType.GIVE_ITEM) {
            ItemSafetyResult itemResult = itemSafetyGuard.check(validation.parameters(), brainId);
            ctx.itemSafetyResult = itemResult;  // 保存结果，供 ActionExecutor 使用
            if (!itemResult.safe()) {
                logger.warning(String.format("[%s][ResponseStage] 物品安全检查失败: %s", traceId, itemResult.reason()));
                if (auditLogger != null) {
                    auditLogger.logActionBlocked(traceId, player.getUniqueId(), player.getName(), brainId,
                        itemResult.reason(), ActionType.GIVE_ITEM);
                }
                ctx.shortCircuit = true;
                ctx.failedStage = type();
                ctx.failureReason = "物品安全检查失败: " + itemResult.reason();
                return;
            }
        }
    }
}
