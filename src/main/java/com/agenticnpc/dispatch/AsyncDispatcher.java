package com.agenticnpc.dispatch;

import com.agenticnpc.audit.AuditLogger;
import com.agenticnpc.audit.TokenTracker;
import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.console.MetricsCollector;
import com.agenticnpc.console.PromptSnapshotStore;
import com.agenticnpc.console.RecentInteractionStore;
import com.agenticnpc.context.PromptBuilder;
import com.agenticnpc.dispatch.pipeline.*;
import com.agenticnpc.dispatch.pipeline.stages.*;
import com.agenticnpc.gateway.ActionValidator;
import com.agenticnpc.gateway.ItemSafetyGuard;
import com.agenticnpc.gateway.LLMResponseParser;
import com.agenticnpc.gateway.model.ItemSafetyResult;
import com.agenticnpc.gateway.model.ValidationResult;
import com.agenticnpc.gateway.SemanticGuard;
import com.agenticnpc.hook.ChatCollector;
import com.agenticnpc.memory.MemoryManager;
import com.agenticnpc.model.*;
import com.agenticnpc.pipeline.InteractionPipeline;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * 异步调度器（核心管线编排器）。
 *
 * 职责：
 * 1. submit() — async scheduling
 * 2. processAsync() — 创建 PipelineContext → PipelineExecutor.run(ctx) → 处理结果
 * 3. 顶层异常处理 + 短路恢复
 * 4. 主线程执行（仅 syncToMain 部分）
 *
 * 具体业务步骤由各 PipelineStage 承担。
 */
public class AsyncDispatcher implements InteractionPipeline {

    private final ConfigManager config;
    private final Plugin        plugin;
    private final Logger        logger;

    // Pipeline infrastructure
    private PipelineExecutor pipelineExecutor;
    private LLMClient        llmClient;  // 保留引用以支持 setLLMClient 热重载

    // 用于主线程执行和恢复
    private ChatCollector     chatCollector;
    private ExecutionCallback executionCallback;
    private MetricsCollector  metricsCollector;
    private HealthCommandAccessor healthCommand;

    // 阶段依赖（用于重建 pipeline）
    private final RateLimiter        rateLimiter;
    private final CircuitBreaker     circuitBreaker;
    private final PromptBuilder      promptBuilder;
    private final LLMResponseParser  responseParser;
    private final ActionValidator    actionValidator;
    private final ItemSafetyGuard    itemSafetyGuard;
    private final MemoryManager      memoryManager;
    private AuditLogger              auditLogger;
    private TokenTracker             tokenTracker;
    private SemanticGuard            semanticGuard;
    private RecentInteractionStore   interactionStore;
    private PromptSnapshotStore      promptStore;

    public AsyncDispatcher(
            RateLimiter       rateLimiter,
            CircuitBreaker    circuitBreaker,
            LLMClient         llmClient,
            PromptBuilder     promptBuilder,
            LLMResponseParser responseParser,
            ActionValidator   actionValidator,
            ItemSafetyGuard   itemSafetyGuard,
            MemoryManager     memoryManager,
            ChatCollector     chatCollector,
            ConfigManager     config,
            Plugin            plugin,
            Logger            logger) {
        this.rateLimiter     = rateLimiter;
        this.circuitBreaker  = circuitBreaker;
        this.llmClient       = llmClient;
        this.promptBuilder   = promptBuilder;
        this.responseParser  = responseParser;
        this.actionValidator = actionValidator;
        this.itemSafetyGuard = itemSafetyGuard;
        this.memoryManager   = memoryManager;
        this.chatCollector   = chatCollector;
        this.config          = config;
        this.plugin          = plugin;
        this.logger          = logger;
    }

    // ---- Setter methods for post-construction wiring ----

    public void setChatCollector(ChatCollector chatCollector) {
        this.chatCollector = chatCollector;
    }

    public void setExecutionCallback(ExecutionCallback callback) {
        this.executionCallback = callback;
    }

    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
        rebuildPipeline();
    }

    public void setTokenTracker(TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
        rebuildPipeline();
    }

    public void setLLMClient(LLMClient llmClient) {
        this.llmClient = llmClient;
        rebuildPipeline();
    }

    public void setSemanticGuard(SemanticGuard semanticGuard) {
        this.semanticGuard = semanticGuard;
        rebuildPipeline();
    }

    public void setHealthCommand(com.agenticnpc.command.HealthCommand healthCommand) {
        this.healthCommand = healthCommand != null ? healthCommand::recordError : null;
    }

    public void setMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
        rebuildPipeline();
    }

    public void setInteractionStore(RecentInteractionStore interactionStore) {
        this.interactionStore = interactionStore;
        rebuildPipeline();
    }

    public void setPromptStore(PromptSnapshotStore promptStore) {
        this.promptStore = promptStore;
        rebuildPipeline();
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    // ---- Core pipeline orchestration ----

    @Override
    public void submit(InteractionEvent event) {
        if (metricsCollector != null) metricsCollector.setQueueDepth(
            metricsCollector.snapshot().queueDepth() + 1);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (metricsCollector != null) {
                metricsCollector.setQueueDepth(
                    metricsCollector.snapshot().queueDepth() - 1);
                metricsCollector.recordInflightStart();
            }
            try {
                processAsync(event);
            } finally {
                if (metricsCollector != null) metricsCollector.recordInflightEnd();
            }
        });
    }

    private void processAsync(InteractionEvent event) {
        Player player  = event.player();
        String brainId = event.npcBrainId();
        String traceId = event.traceId();

        BrainConfig brain = config.getBrainConfig(brainId).orElse(null);
        if (brain == null) {
            logger.warning(String.format("[%s][Dispatcher] 找不到 BrainConfig: %s", traceId, brainId));
            restoreListening(event);
            return;
        }

        // 创建 pipeline context
        PipelineContext ctx = new PipelineContext(traceId, event, brain);
        ctx.pipelineStartMs = System.currentTimeMillis();

        try {
            // 执行 pipeline
            if (pipelineExecutor == null) {
                rebuildPipeline();
            }
            pipelineExecutor.run(ctx);
        } catch (Exception e) {
            // 顶层异常兜底
            logger.warning(String.format("[%s][Dispatcher] Pipeline 异常: %s", traceId, e.getMessage()));
            ctx.shortCircuit = true;
            ctx.failedStage = PipelineStageType.EXECUTION;
            ctx.failureReason = "Pipeline 异常: " + e.getMessage();
        }

        // 处理结果
        if (ctx.shortCircuit) {
            // 短路恢复：发降级消息 + 恢复 LISTENING
            handleShortCircuit(ctx, player, brain);
            return;
        }

        // 成功路径：主线程执行（仅 Bukkit API 部分 sync）
        final LLMResponse response = ctx.response;
        final ValidationResult validation = ctx.validation;
        final String finalTraceId = traceId;

        logger.info(String.format("[%s][Dispatcher] Pipeline 完成，准备主线程执行 | callback=%s | response=%s | validation=%s",
            traceId,
            executionCallback != null ? executionCallback.getClass().getSimpleName() : "NULL",
            response != null ? "ok" : "NULL",
            validation != null ? "ok" : "NULL"
        ));

        syncToMain(() -> {
            try {
                logger.info(String.format("[%s][Dispatcher] 主线程回调执行 | callback=%s",
                    finalTraceId,
                    executionCallback != null ? executionCallback.getClass().getSimpleName() : "NULL"
                ));
                if (executionCallback != null) {
                    executionCallback.execute(finalTraceId, player, response, validation, ctx.itemSafetyResult, brain);
                } else {
                    player.sendMessage("§e[" + brain.name() + "] §f" + response.dialogue());
                }
                chatCollector.markListeningAfterResponse(player.getUniqueId());
            } catch (Exception e) {
                logger.severe(String.format("[%s][Dispatcher] 主线程回调异常: %s", finalTraceId, e.getMessage()));
                e.printStackTrace();
                chatCollector.markListeningAfterResponse(player.getUniqueId());
            }
        });

        // 记录 health error（如果有 healthCommand 且 failedStage != null）
        if (ctx.failedStage != null && healthCommand != null) {
            healthCommand.recordError();
        }
    }

    /**
     * 短路恢复：发降级消息 + 恢复 LISTENING 状态。
     * 保证玩家不会卡死在 PROCESSING。
     */
    private void handleShortCircuit(PipelineContext ctx, Player player, BrainConfig brain) {
        String traceId = ctx.traceId;
        String failedStageName = ctx.failedStage != null ? ctx.failedStage.name() : "unknown";

        // 记录 metrics
        if (metricsCollector != null) {
            metricsCollector.recordFailure();
            if (ctx.failedStage != null) {
                metricsCollector.recordStageFailure(ctx.failedStage);
            }
            // 记录已完成 stage 的 latency
            for (var entry : ctx.stageLatencyMs.entrySet()) {
                metricsCollector.recordStageLatency(entry.getKey(), entry.getValue());
            }
        }

        // Health error tracking
        if (healthCommand != null) {
            healthCommand.recordError();
        }

        // 发送降级消息
        if (ctx.response != null && ctx.response.dialogue() != null && !ctx.response.dialogue().isBlank()) {
            // 有 dialogue 但校验失败 → 发 dialogue
            syncToMain(() -> player.sendMessage("§e[" + brain.name() + "] §f" + ctx.response.dialogue()));
        } else {
            // 无 dialogue → 发 fallback
            sendFallback(player, brain);
        }

        // 恢复 LISTENING 状态
        restoreListening(ctx.event);

        logger.info(String.format("[%s][Dispatcher] 短路恢复 | stage=%s | reason=%s",
            traceId, failedStageName, ctx.failureReason));
    }

    // ---- Pipeline rebuild (when dependencies change) ----

    private synchronized void rebuildPipeline() {
        PipelineStage[] stages = new PipelineStage[] {
            new GuardStage(rateLimiter, semanticGuard, auditLogger, logger),
            new PromptStage(promptBuilder, config, logger),
            new LLMStage(circuitBreaker, llmClient, tokenTracker, auditLogger, logger),
            new ResponseStage(responseParser, actionValidator, itemSafetyGuard,
                              auditLogger, metricsCollector, logger),
            new ExecutionStage(memoryManager, auditLogger, metricsCollector,
                                interactionStore, promptStore, logger)
        };
        this.pipelineExecutor = new PipelineExecutor(stages, logger);
    }

    // ---- Utility methods ----

    private void sendFallback(Player player, BrainConfig brain) {
        String fallback = brain.fallbackDialogue() != null
            ? brain.fallbackDialogue() : config.getFallbackDialogue();
        syncToMain(() -> player.sendMessage("§e[" + brain.name() + "] §7" + fallback));
    }

    private void restoreListening(InteractionEvent event) {
        if (chatCollector != null) {
            chatCollector.markListeningAfterResponse(event.player().getUniqueId());
        }
    }

    private void syncToMain(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    @FunctionalInterface
    public interface ExecutionCallback {
        void execute(String traceId, Player player, LLMResponse response, ValidationResult validation,
                     ItemSafetyResult itemSafetyResult, BrainConfig brain);
    }

    /** HealthCommand 访问器（避免直接依赖命令类） */
    @FunctionalInterface
    private interface HealthCommandAccessor {
        void recordError();
    }
}
