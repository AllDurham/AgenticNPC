package com.agenticnpc.dispatch.pipeline.stages;

import com.agenticnpc.audit.AuditLogger;
import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.console.MetricsCollector;
import com.agenticnpc.console.PromptSnapshotStore;
import com.agenticnpc.console.RecentInteractionStore;
import com.agenticnpc.dispatch.pipeline.PipelineContext;
import com.agenticnpc.dispatch.pipeline.PipelineStage;
import com.agenticnpc.dispatch.pipeline.PipelineStageType;
import com.agenticnpc.dispatch.pipeline.StageException;
import com.agenticnpc.memory.MemoryManager;
import com.agenticnpc.model.ChatMessage;
import com.agenticnpc.model.InteractionEvent;
import com.agenticnpc.model.LLMResponse;
import com.agenticnpc.model.PromptPackage;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.logging.Logger;

/**
 * ExecutionStage：记忆追加 + 审计 + Metrics + Snapshot + 主线程执行。
 *
 * 线程策略：
 * - 异步部分：memory/audit/metrics/stores（降级组件，catch + warn）
 * - 同步部分：仅 ActionExecutor + player.sendMessage（由调用方通过 syncToMain 执行）
 *
 * 关键：memory/audit failure 不能阻止 NPC 回复玩家。
 */
public class ExecutionStage implements PipelineStage {

    private final MemoryManager          memoryManager;
    private final AuditLogger            auditLogger;
    private final MetricsCollector       metricsCollector;
    private final RecentInteractionStore interactionStore;
    private final PromptSnapshotStore    promptStore;
    private final Logger                 logger;

    public ExecutionStage(MemoryManager memoryManager, AuditLogger auditLogger,
                          MetricsCollector metricsCollector,
                          RecentInteractionStore interactionStore,
                          PromptSnapshotStore promptStore,
                          Logger logger) {
        this.memoryManager    = memoryManager;
        this.auditLogger      = auditLogger;
        this.metricsCollector = metricsCollector;
        this.interactionStore = interactionStore;
        this.promptStore      = promptStore;
        this.logger           = logger;
    }

    @Override
    public String name() { return "ExecutionStage"; }

    @Override
    public PipelineStageType type() { return PipelineStageType.EXECUTION; }

    @Override
    public void execute(PipelineContext ctx) {
        InteractionEvent event = ctx.event;
        Player player = event.player();
        String brainId = event.npcBrainId();
        String traceId = ctx.traceId;
        LLMResponse response = ctx.response;
        boolean isStress = event.stress();

        // ---- 记忆追加（降级组件，stress 时跳过）----
        if (!isStress) {
            try {
                memoryManager.appendAndPersist(
                    player.getUniqueId(), brainId,
                    new ChatMessage("user", event.sanitizedInput()),
                    new ChatMessage("assistant", response.dialogue())
                );
            } catch (Exception e) {
                logger.warning(String.format("[%s][ExecutionStage] 记忆追加失败（降级）: %s", traceId, e.getMessage()));
            }
        }

        // ---- 审计日志（降级组件，stress 时跳过）----
        if (!isStress && auditLogger != null) {
            try {
                auditLogger.logDialogueSuccess(
                    traceId, player.getUniqueId(), player.getName(), brainId,
                    event.sanitizedInput(), response.dialogue(),
                    ctx.validation.actionType(),
                    ctx.validation.parameters() != null ? ctx.validation.parameters().toString() : null
                );
            } catch (Exception e) {
                logger.warning(String.format("[%s][ExecutionStage] 审计写入失败（降级）: %s", traceId, e.getMessage()));
            }
        }

        // ---- Token 解析（从 LLM 响应体）----
        int promptTok = 0, completionTok = 0, totalTok = 0;
        try {
            com.google.gson.JsonObject root =
                com.google.gson.JsonParser.parseString(ctx.llmFullResponseBody).getAsJsonObject();
            com.google.gson.JsonObject usage = root.getAsJsonObject("usage");
            if (usage != null) {
                promptTok = usage.get("prompt_tokens").getAsInt();
                completionTok = usage.get("completion_tokens").getAsInt();
                totalTok = usage.get("total_tokens").getAsInt();
            }
        } catch (Exception ignored) {}

        // ---- Metrics ----
        long pipelineLatency = System.currentTimeMillis() - ctx.pipelineStartMs;
        if (metricsCollector != null) {
            metricsCollector.recordSuccess(
                ctx.validation.actionType(), pipelineLatency,
                promptTok, completionTok, totalTok, ctx.parseFallback
            );
            metricsCollector.recordPipelineLatency(pipelineLatency);

            // Record fallback type
            if (ctx.fallbackType != null && ctx.fallbackType != com.agenticnpc.gateway.LLMResponseParser.FallbackType.NONE) {
                metricsCollector.recordFallbackType(ctx.fallbackType.name());
            }

            // Record stage latencies
            for (var entry : ctx.stageLatencyMs.entrySet()) {
                metricsCollector.recordStageLatency(entry.getKey(), entry.getValue());
            }

            // Pipeline snapshot
            EnumMap<PipelineStageType, Long> stageMs = new EnumMap<>(ctx.stageLatencyMs);
            metricsCollector.addPipelineSnapshot(new MetricsCollector.PipelineSnapshot(
                traceId, pipelineLatency, stageMs, ctx.failedStage != null ? ctx.failedStage.name() : null
            ));

            // v1.4-c: Record variant metrics
            boolean hasValidAction = ctx.validation != null && ctx.validation.valid()
                && ctx.validation.actionType() != null
                && ctx.validation.actionType() != com.agenticnpc.model.ActionType.NONE;
            metricsCollector.recordVariantMetrics(
                ctx.variant, totalTok, pipelineLatency, ctx.parseFallback, hasValidAction
            );
        }

        // ---- Prompt Snapshot（stress 时跳过，避免污染观测数据）----
        if (!isStress && promptStore != null) {
            String historyStr = "";
            if (ctx.promptPackage != null && ctx.promptPackage.history() != null) {
                historyStr = ctx.promptPackage.history().stream()
                    .map(m -> m.role() + ": " + m.content())
                    .reduce("", (a, b) -> a + "\n" + b);
            }
            // Token breakdown
            int sysTok = 0, profTok = 0, sumTok = 0, emoTok = 0, plTok = 0, actTok = 0, fmtTok = 0, histTok = 0, inpTok = 0;
            if (ctx.breakdown != null) {
                sysTok = ctx.breakdown.systemTokens();
                profTok = ctx.breakdown.profileTokens();
                sumTok = ctx.breakdown.summaryTokens();
                emoTok = ctx.breakdown.emotionTokens();
                plTok = ctx.breakdown.playerInfoTokens();
                actTok = ctx.breakdown.actionTokens();
                fmtTok = ctx.breakdown.formatTokens();
                histTok = ctx.breakdown.historyTokens();
                inpTok = ctx.breakdown.userInputTokens();
            }

            String fallbackName = ctx.fallbackType != null ? ctx.fallbackType.name() : "NONE";
            int fixedCost = ctx.breakdown != null ? ctx.breakdown.fixedCost() : 0;
            String variantName = ctx.variant != null ? ctx.variant : "current";

            promptStore.add(new PromptSnapshotStore.PromptSnapshot(
                traceId, System.currentTimeMillis(),
                player.getName(), player.getUniqueId().toString(),
                brainId, ctx.brain.name(),
                ctx.promptPackage != null ? ctx.promptPackage.systemPrompt() : "",
                historyStr, event.sanitizedInput(),
                ctx.llmRawContent,
                ctx.validation.actionType() != null ? ctx.validation.actionType().name() : "NONE",
                promptTok, completionTok, totalTok,
                sysTok, profTok, sumTok, emoTok, plTok, actTok, fmtTok, histTok, inpTok,
                fallbackName,
                variantName,
                fixedCost
            ));
        }

        // ---- Recent Interaction（stress 时跳过）----
        if (!isStress && interactionStore != null) {
            interactionStore.add(new RecentInteractionStore.InteractionRecord(
                traceId, System.currentTimeMillis(),
                player.getName(), brainId,
                event.sanitizedInput(),
                ctx.validation.actionType(),
                totalTok, pipelineLatency,
                ctx.parseFallback
            ));
        }

        // 注意：主线程执行（ActionExecutor + sendMessage）由 AsyncDispatcher 在 stage 完成后处理
    }
}
