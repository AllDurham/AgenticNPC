package com.agenticnpc.dispatch;

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
 * 实现 InteractionPipeline 接口，替换 Phase 3 的 placeholder。
 *
 * 完整处理链路（全部在 Bukkit 异步线程执行，除最后一步）：
 *   submit() 入口
 *     → 限流检查
 *     → 构建 Prompt
 *     → 熔断器 → LLM 请求
 *     → JSON 解析
 *     → 动作校验
 *     → 物品安全检查
 *     → 记忆追加 + 持久化
 *     → 主线程同步 → 执行层
 */
public class AsyncDispatcher implements InteractionPipeline {

    private final RateLimiter        rateLimiter;
    private final CircuitBreaker     circuitBreaker;
    private final LLMClient          llmClient;
    private final PromptBuilder      promptBuilder;
    private final LLMResponseParser  responseParser;
    private final ActionValidator    actionValidator;
    private final ItemSafetyGuard    itemSafetyGuard;
    private final MemoryManager      memoryManager;
    private final ConfigManager      config;
    private final Plugin             plugin;
    private final Logger             logger;

    // 非 final，通过 setter 注入解决循环依赖
    private ChatCollector chatCollector;

    // 执行层回调（Phase 5 注入，Phase 4 阶段用 placeholder）
    private ExecutionCallback executionCallback;

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

    /** 注入 ChatCollector（解决循环依赖）*/
    public void setChatCollector(ChatCollector chatCollector) {
        this.chatCollector = chatCollector;
    }

    /** 注入执行层回调（Phase 5 调用） */
    public void setExecutionCallback(ExecutionCallback callback) {
        this.executionCallback = callback;
    }

    // ================================================================
    // 管线入口（在 Bukkit 异步线程调用）
    // ================================================================

    @Override
    public void submit(InteractionEvent event) {
        // submit 由 AsyncPlayerChatEvent 在异步线程触发，符合要求
        // 使用 Bukkit 异步任务包装，确保不意外阻塞事件线程
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            processAsync(event)
        );
    }

    // ================================================================
    // 异步处理主链路
    // ================================================================

    private void processAsync(InteractionEvent event) {
        Player player  = event.player();
        String brainId = event.npcBrainId();

        BrainConfig brain = config.getBrainConfig(brainId).orElse(null);
        if (brain == null) {
            logger.warning("[Dispatcher] 找不到 BrainConfig: " + brainId);
            restoreListening(event);
            return;
        }

        // ---- Step 1: 限流检查（本地计算，不走网络）----
        var limitResult = rateLimiter.tryAcquire(player.getUniqueId(), brainId);
        if (!limitResult.allowed()) {
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
            () -> null  // 熔断时返回 null，后续步骤处理
        ).whenComplete((rawContent, throwable) -> {
            // 所有后续处理仍在异步线程
            if (throwable != null) {
                logger.warning("[Dispatcher] LLM 请求异常: " + throwable.getMessage());
                sendFallback(player, brain);
                restoreListening(event);
                return;
            }

            if (rawContent == null) {
                // 熔断降级
                sendFallback(player, brain);
                restoreListening(event);
                return;
            }

            // ---- Step 4: 解析 LLM 响应 ----
            var parsedOpt = responseParser.parse(rawContent);
            if (parsedOpt.isEmpty()) {
                // 保存省略号占位到记忆，防止上下文断裂
                memoryManager.appendAndPersist(
                    player.getUniqueId(),
                    brainId,
                    new ChatMessage("user",      event.sanitizedInput()),
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
                // 校验失败时仍然发送 dialogue（NPC 说话但不执行动作）
                sendDialogueOnly(player, response.dialogue(), brain);
                restoreListening(event);
                return;
            }

            // ---- Step 6: 物品安全检查（仅 GIVE_ITEM）----
            ItemSafetyResult itemSafetyResult = null;
            if (validation.actionType() == ActionType.GIVE_ITEM) {
                itemSafetyResult = itemSafetyGuard.check(validation.parameters(), brainId);
                if (!itemSafetyResult.safe()) {
                    logger.warning("[Dispatcher] 物品安全检查失败: " + itemSafetyResult.reason());
                    sendDialogueOnly(player, response.dialogue(), brain);
                    restoreListening(event);
                    return;
                }
            }

            // ---- Step 7: 更新对话记忆（异步持久化）----
            memoryManager.appendAndPersist(
                player.getUniqueId(),
                brainId,
                new ChatMessage("user",      event.sanitizedInput()),
                new ChatMessage("assistant", response.dialogue())
            );

            // ---- Step 8: 同步回主线程执行 ----
            final ItemSafetyResult finalItemResult = itemSafetyResult;
            syncToMain(() -> {
                if (executionCallback != null) {
                    executionCallback.execute(player, response, validation,
                        finalItemResult, brain);
                } else {
                    // Phase 4 阶段：暂无执行层，仅发送台词验证链路
                    player.sendMessage("§e[" + brain.name() + "] §f" + response.dialogue());
                    logger.info("[Dispatcher] Phase 4 placeholder 执行 | 台词: "
                        + response.dialogue()
                        + " | 动作: " + validation.actionType());
                }

                // 恢复 LISTENING 状态，允许玩家继续对话
                chatCollector.markListeningAfterResponse(player.getUniqueId());
            });
        });
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /** 发送降级话术（异步线程调用，切回主线程发消息）*/
    private void sendFallback(Player player, BrainConfig brain) {
        String fallback = brain.fallbackDialogue() != null
            ? brain.fallbackDialogue()
            : config.getFallbackDialogue();
        syncToMain(() -> player.sendMessage("§e[" + brain.name() + "] §7" + fallback));
    }

    /** 仅发送台词，不执行动作（校验失败时的降级）*/
    private void sendDialogueOnly(Player player, String dialogue, BrainConfig brain) {
        if (dialogue != null && !dialogue.isBlank()) {
            syncToMain(() ->
                player.sendMessage("§e[" + brain.name() + "] §f" + dialogue)
            );
        } else {
            sendFallback(player, brain);
        }
    }

    /** 恢复玩家 LISTENING 状态（在当前线程直接调用，线程安全）*/
    private void restoreListening(InteractionEvent event) {
        if (chatCollector != null) {
            chatCollector.markListeningAfterResponse(event.player().getUniqueId());
        }
    }

    /** 提交任务到 Bukkit 主线程 */
    private void syncToMain(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    // ================================================================
    // 执行层回调接口（Phase 5 实现）
    // ================================================================

    @FunctionalInterface
    public interface ExecutionCallback {
        void execute(Player player,
                     LLMResponse response,
                     ValidationResult validation,
                     ItemSafetyResult itemSafetyResult,
                     BrainConfig brain);
    }
}
