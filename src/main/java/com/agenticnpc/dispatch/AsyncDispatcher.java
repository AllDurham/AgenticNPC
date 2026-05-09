package com.agenticnpc.dispatch;

import com.agenticnpc.audit.AuditLogger;
import com.agenticnpc.audit.TokenTracker;
import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.context.PromptBuilder;
import com.agenticnpc.gateway.ActionValidator;
import com.agenticnpc.gateway.ItemSafetyGuard;
import com.agenticnpc.gateway.LLMResponseParser;
import com.agenticnpc.gateway.model.ItemSafetyResult;
import com.agenticnpc.gateway.model.ValidationResult;
import com.agenticnpc.hook.ChatCollector;
import com.agenticnpc.memory.MemoryManager;
import com.agenticnpc.model.*;
import com.agenticnpc.pipeline.InteractionPipeline;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * 异步调度器（核心管线实现）。
 *
 * 完整处理链路（全部在 Bukkit 异步线程执行，除最后一步）：
 *   submit() → 限流 → Prompt → 熔断器 → LLM → 解析 → 校验 → 安全检查
 *   → 记忆追加 → 审计日志 → Token 统计 → 主线程执行
 */
public class AsyncDispatcher implements InteractionPipeline {

    private final RateLimiter        rateLimiter;
    private final CircuitBreaker     circuitBreaker;
    private LLMClient                llmClient;
    private final PromptBuilder      promptBuilder;
    private final LLMResponseParser  responseParser;
    private final ActionValidator    actionValidator;
    private final ItemSafetyGuard    itemSafetyGuard;
    private final MemoryManager      memoryManager;
    private final ConfigManager      config;
    private final Plugin             plugin;
    private final Logger             logger;

    private ChatCollector    chatCollector;
    private ExecutionCallback executionCallback;
    private AuditLogger      auditLogger;
    private TokenTracker     tokenTracker;

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

    public void setChatCollector(ChatCollector chatCollector) {
        this.chatCollector = chatCollector;
    }

    public void setExecutionCallback(ExecutionCallback callback) {
        this.executionCallback = callback;
    }

    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    public void setTokenTracker(TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
    }

    public void setLLMClient(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    @Override
    public void submit(InteractionEvent event) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            processAsync(event)
        );
    }

    private void processAsync(InteractionEvent event) {
        Player player  = event.player();
        String brainId = event.npcBrainId();

        BrainConfig brain = config.getBrainConfig(brainId).orElse(null);
        if (brain == null) {
            logger.warning("[Dispatcher] 找不到 BrainConfig: " + brainId);
            restoreListening(event);
            return;
        }

        // ---- Step 1: 限流检查 ----
        var limitResult = rateLimiter.tryAcquire(player.getUniqueId(), brainId);
        if (!limitResult.allowed()) {
            if (auditLogger != null) {
                auditLogger.logRateLimited(player.getUniqueId(), player.getName(), brainId);
            }
            syncToMain(() ->
                player.sendMessage("§7[" + brain.name() + "] §e" + limitResult.rejectMessage())
            );
            restoreListening(event);
            return;
        }

        // ---- Step 2: 构建 Prompt ----
        PromptPackage promptPackage;
        try {
            promptPackage = promptBuilder.build(event);
        } catch (Exception e) {
            logger.warning("[Dispatcher] Prompt 构建失败: " + e.getMessage());
            sendFallback(player, brain);
            restoreListening(event);
            return;
        }

        // ---- Step 3: 通过熔断器发起 LLM 请求 ----
        circuitBreaker.execute(
            () -> llmClient.sendAsync(promptPackage),
            () -> null
        ).whenComplete((llmResult, throwable) -> {
            if (throwable != null) {
                logger.warning("[Dispatcher] LLM 请求异常: " + throwable.getMessage());
                if (auditLogger != null) {
                    auditLogger.logParseFailed(player.getUniqueId(), player.getName(),
                        brainId, throwable.getMessage());
                }
                sendFallback(player, brain);
                restoreListening(event);
                return;
            }

            if (llmResult == null) {
                if (auditLogger != null) {
                    auditLogger.logCircuitOpen(brainId, 0);
                }
                sendFallback(player, brain);
                restoreListening(event);
                return;
            }

            // Token 统计
            if (tokenTracker != null) {
                tokenTracker.track(llmResult.fullResponseBody(),
                    player.getUniqueId(), player.getName(), brainId);
            }

            String rawContent = llmResult.contentText();

            // ---- Step 4: 解析 LLM 响应 ----
            var parsedOpt = responseParser.parse(rawContent);
            if (parsedOpt.isEmpty()) {
                if (auditLogger != null) {
                    auditLogger.logParseFailed(player.getUniqueId(), player.getName(),
                        brainId, rawContent);
                }
                memoryManager.appendAndPersist(
                    player.getUniqueId(), brainId,
                    new ChatMessage("user", event.sanitizedInput()),
                    new ChatMessage("assistant", "...")
                );
                syncToMain(() ->
                    player.sendMessage("§e[" + brain.name() + "] §7...（沉默）")
                );
                restoreListening(event);
                return;
            }

            LLMResponse response = parsedOpt.get();

            // ---- Step 5: 动作校验 ----
            ValidationResult validation = actionValidator.validate(response, brainId);
            if (!validation.valid()) {
                logger.warning("[Dispatcher] 动作校验失败: " + validation.failReason());
                if (auditLogger != null) {
                    auditLogger.logActionBlocked(player.getUniqueId(), player.getName(),
                        brainId, validation.failReason(), validation.actionType());
                }
                sendDialogueOnly(player, response.dialogue(), brain);
                restoreListening(event);
                return;
            }

            // ---- Step 6: 物品安全检查 ----
            ItemSafetyResult itemSafetyResult = null;
            if (validation.actionType() == ActionType.GIVE_ITEM) {
                itemSafetyResult = itemSafetyGuard.check(validation.parameters(), brainId);
                if (!itemSafetyResult.safe()) {
                    if (auditLogger != null) {
                        auditLogger.logActionBlocked(player.getUniqueId(), player.getName(),
                            brainId, itemSafetyResult.reason(), ActionType.GIVE_ITEM);
                    }
                    sendDialogueOnly(player, response.dialogue(), brain);
                    restoreListening(event);
                    return;
                }
            }

            // ---- Step 7: 记忆追加 ----
            memoryManager.appendAndPersist(
                player.getUniqueId(), brainId,
                new ChatMessage("user", event.sanitizedInput()),
                new ChatMessage("assistant", response.dialogue())
            );

            // ---- 审计日志 ----
            if (auditLogger != null) {
                auditLogger.logDialogueSuccess(
                    player.getUniqueId(), player.getName(), brainId,
                    event.sanitizedInput(), response.dialogue(),
                    validation.actionType(),
                    validation.parameters() != null ? validation.parameters().toString() : null
                );
            }

            // ---- Step 8: 主线程执行 ----
            final ItemSafetyResult finalItemResult = itemSafetyResult;
            syncToMain(() -> {
                if (executionCallback != null) {
                    executionCallback.execute(player, response, validation,
                        finalItemResult, brain);
                } else {
                    player.sendMessage("§e[" + brain.name() + "] §f" + response.dialogue());
                }
                chatCollector.markListeningAfterResponse(player.getUniqueId());
            });
        });
    }

    private void sendFallback(Player player, BrainConfig brain) {
        String fallback = brain.fallbackDialogue() != null
            ? brain.fallbackDialogue() : config.getFallbackDialogue();
        syncToMain(() -> player.sendMessage("§e[" + brain.name() + "] §7" + fallback));
    }

    private void sendDialogueOnly(Player player, String dialogue, BrainConfig brain) {
        if (dialogue != null && !dialogue.isBlank()) {
            syncToMain(() -> player.sendMessage("§e[" + brain.name() + "] §f" + dialogue));
        } else {
            sendFallback(player, brain);
        }
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
        void execute(Player player, LLMResponse response, ValidationResult validation,
                     ItemSafetyResult itemSafetyResult, BrainConfig brain);
    }
}
