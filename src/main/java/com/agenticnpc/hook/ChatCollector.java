package com.agenticnpc.hook;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.gateway.InputSanitizer;
import com.agenticnpc.gateway.model.SanitizeResult;
import com.agenticnpc.model.InteractionEvent;
import com.agenticnpc.pipeline.InteractionPipeline;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 玩家与 NPC 的对话会话状态机。
 *
 * 状态转移：
 *
 *  [无会话] --右键NPC--> [LISTENING] --发送消息--> [PROCESSING] --响应完成--> [LISTENING]
 *               ▲              │
 *               │              ├── 超时(30s)
 *               │              ├── 走远(5格)
 *               │              ├── 输入"取消"
 *               │              └── 死亡/退出
 *               └──────────────(以上均清除会话)
 */
public class ChatCollector implements Listener {

    // ---- 状态枚举 ----
    public enum SessionState { LISTENING, PROCESSING }

    /**
     * 单个玩家的会话上下文（不可变快照，替换整个对象来更新状态）。
     */
    private record ListeningSession(
        String       npcBrainId,
        String       npcBrainName,
        Entity       npcEntity,
        long         startTimeMs,
        long         lastReminderMs,
        SessionState state
    ) {
        ListeningSession withState(SessionState newState) {
            return new ListeningSession(npcBrainId, npcBrainName, npcEntity, startTimeMs, lastReminderMs, newState);
        }

        ListeningSession resetTimer() {
            return new ListeningSession(npcBrainId, npcBrainName, npcEntity,
                System.currentTimeMillis(), lastReminderMs, state);
        }

        ListeningSession updateReminderTime(long now) {
            return new ListeningSession(npcBrainId, npcBrainName, npcEntity,
                startTimeMs, now, state);
        }
    }

    // ---- 配置常量 ----
    private static final int    MAX_DISTANCE_BLOCKS = 5;
    private static final String CANCEL_KEYWORD      = "取消";

    // ---- 依赖 ----
    private final ConcurrentHashMap<UUID, ListeningSession> sessions
        = new ConcurrentHashMap<>();

    private final InteractionPipeline pipeline;
    private final InputSanitizer      sanitizer;
    private final ConfigManager       config;
    private final Plugin              plugin;
    private final Logger              logger;

    public ChatCollector(InteractionPipeline pipeline,
                         InputSanitizer sanitizer,
                         ConfigManager config,
                         Plugin plugin,
                         Logger logger) {
        this.pipeline  = pipeline;
        this.sanitizer = sanitizer;
        this.config    = config;
        this.plugin    = plugin;
        this.logger    = logger;

        startTimeoutChecker();
    }

    // ================================================================
    // 公开 API：由 Layer 1 各 Hook 在右键 NPC 时调用
    // ================================================================

    /**
     * 将玩家置入 LISTENING 状态。
     * 必须在主线程调用（来自事件 Handler）。
     *
     * @param player    触发交互的玩家
     * @param npcEntity 被右键的 NPC 实体
     * @param brainId   绑定的 Brain ID
     */
    public void startListening(Player player, Entity npcEntity, String brainId) {
        UUID playerId = player.getUniqueId();

        // 如果已有进行中的会话，先取消旧会话（不发消息，静默替换）
        sessions.remove(playerId);

        // 获取 NPC 名称（用于 Title 提示 + 会话记录）
        String brainName = config.getBrainConfig(brainId)
            .map(b -> b.name())
            .orElse("NPC");

        sessions.put(playerId, new ListeningSession(
            brainId,
            brainName,
            npcEntity,
            System.currentTimeMillis(),
            0L,
            SessionState.LISTENING
        ));

        // Title 提示比聊天消息更沉浸
        player.sendTitle(
            "§e" + brainName,
            "§7在聊天框输入内容对话，输入「取消」退出",
            10, 80, 20
        );

        logger.info(String.format("[ChatCollector] 开始监听 | 玩家: %s | Brain: %s",
            player.getName(), brainId));
    }

    /**
     * 将会话标记为处理中（防止玩家重复提交）。
     * 显示"正在思考..."ActionBar。
     * 可在任意线程调用（ConcurrentHashMap 保证线程安全）。
     */
    public void markProcessing(UUID playerId) {
        sessions.computeIfPresent(playerId, (id, session) ->
            session.withState(SessionState.PROCESSING)
        );

        // 显示"正在思考..."ActionBar
        if (config.isThinkingActionBarEnabled()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                ListeningSession session = sessions.get(playerId);
                String name = session != null ? session.npcBrainName() : "NPC";
                syncToMain(() ->
                    player.spigot().sendMessage(
                        net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        new net.md_5.bungee.api.chat.TextComponent("§6" + name + "正在思考...")
                    )
                );
            }
        }
    }

    /**
     * LLM 响应完成后，将会话恢复为 LISTENING（允许继续对话）。
     * 清除"正在思考..."ActionBar。
     * 可在任意线程调用。
     */
    public void markListeningAfterResponse(UUID playerId) {
        sessions.computeIfPresent(playerId, (id, session) ->
            session.resetTimer().withState(SessionState.LISTENING)
        );

        // 清除 ActionBar（ActionExecutor 的台词渲染会覆盖，但异常路径需要手动清理）
        if (config.isThinkingActionBarEnabled()) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                syncToMain(() ->
                    player.spigot().sendMessage(
                        net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        new net.md_5.bungee.api.chat.TextComponent("")
                    )
                );
            }
        }
    }

    /**
     * 主动结束会话（如 NPC 被删除时调用）。
     */
    public void endSession(UUID playerId) {
        sessions.remove(playerId);
    }

    /**
     * 查询玩家当前是否有活跃会话。
     */
    public boolean hasActiveSession(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    // ================================================================
    // 事件监听
    // ================================================================

    /**
     * 核心：拦截聊天事件，将消息重定向至处理管线。
     * LOWEST 优先级确保我们最先拿到消息。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player   = event.getPlayer();
        UUID   playerId = player.getUniqueId();

        ListeningSession session = sessions.get(playerId);

        // 玩家不在对话状态，正常放行
        if (session == null) return;

        // 拦截消息，不发送到公屏
        event.setCancelled(true);
        String rawInput = event.getMessage();

        // 处理取消指令
        if (CANCEL_KEYWORD.equals(rawInput.trim())) {
            // AsyncPlayerChatEvent 在异步线程，切回主线程操作 Title
            syncToMain(() -> {
                sessions.remove(playerId);
                player.sendMessage("§7[对话] §e已退出与 NPC 的对话。");
                player.resetTitle();
            });
            return;
        }

        // 正在处理中，忽略新输入
        if (session.state() == SessionState.PROCESSING) {
            player.sendMessage("§7NPC 正在思考，请稍候...");
            return;
        }

        // ---- 输入清洗（在异步线程执行，符合要求）----
        SanitizeResult sanitized = sanitizer.sanitize(rawInput, player.getName());
        if (!sanitized.accepted()) {
            player.sendMessage("§c[系统] 输入包含非法内容，已拒绝。");
            return;
        }

        // 对话日志
        logger.info(String.format(
            "[对话] %s → [%s] : %s",
            player.getName(),
            session.npcBrainId(),
            sanitized.value()
        ));

        // 回显玩家消息（仅 AI 会话状态，不广播全服）
        if (config.isChatEchoEnabled()) {
            player.sendMessage("§7[你] §f" + sanitized.value());
        }

        // 标记为处理中，防止重复提交
        markProcessing(playerId);

        // 提交到管线（仍在异步线程，后续处理层保持异步）
        pipeline.submit(new InteractionEvent(
            player,
            session.npcEntity(),
            session.npcBrainId(),
            sanitized.value(),
            System.currentTimeMillis()
        ));
    }

    /**
     * 距离检测：玩家走远自动中断对话。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // 性能优化：只有跨越整数方块边界才检测
        if (!hasCrossedBlockBoundary(event)) return;

        Player player   = event.getPlayer();
        UUID   playerId = player.getUniqueId();

        ListeningSession session = sessions.get(playerId);
        if (session == null) return;

        // NPC 实体已失效
        if (!session.npcEntity().isValid()) {
            sessions.remove(playerId);
            player.sendMessage("§7[对话] §eNPC 消失了，对话已中断。");
            player.resetTitle();
            return;
        }

        // 计算距离
        double distance = player.getLocation()
            .distance(session.npcEntity().getLocation());

        if (distance > MAX_DISTANCE_BLOCKS) {
            sessions.remove(playerId);
            player.sendMessage("§7[对话] §e你走得太远了，对话已中断。");
            player.resetTitle();

            logger.info(String.format("[ChatCollector] 距离中断 | 玩家: %s | 距离: %.1f",
                player.getName(), distance));
        }
    }

    /**
     * 玩家死亡：强制清理会话（死亡时不发消息）。
     */
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        if (sessions.remove(playerId) != null) {
            logger.info("[ChatCollector] 玩家死亡，会话已清理 | UUID: " + playerId);
        }
    }

    /**
     * 玩家退出：强制清理会话，防止内存泄漏。
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    // ================================================================
    // 私有工具方法
    // ================================================================

    /**
     * 启动会话管理定时任务（合并超时检查 + ActionBar 提醒）。
     * 每 3 秒（60 ticks）扫描一次：
     * - LISTENING 超时：驱逐超过配置时间无活动的会话
     * - LISTENING 提醒：定期发送 ActionBar 提示
     * - PROCESSING 状态不超时（等待 LLM，由 HTTP 超时控制）
     */
    private void startTimeoutChecker() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            long timeoutMs = (long) config.getChatSessionTimeoutSeconds() * 1000L;
            long reminderIntervalMs = (long) config.getChatReminderIntervalSeconds() * 1000L;
            boolean reminderEnabled = config.isChatReminderEnabled();

            sessions.entrySet().removeIf(entry -> {
                UUID   playerId = entry.getKey();
                ListeningSession session = entry.getValue();
                Player player = plugin.getServer().getPlayer(playerId);

                if (player == null || !player.isOnline()) return true;

                // ---- PROCESSING：不超时，不提醒 ----
                if (session.state() == SessionState.PROCESSING) {
                    return false;
                }

                // ---- LISTENING：超时检查 ----
                boolean timedOut = (now - session.startTimeMs()) > timeoutMs;
                if (timedOut) {
                    player.sendMessage("§7[AgenticNPC] §e你结束了与 " + session.npcBrainName() + " 的对话。");
                    player.resetTitle();
                    logger.info("[ChatCollector] 会话超时 | 玩家: " + player.getName());
                    return true;
                }

                // ---- LISTENING：ActionBar 提醒 ----
                if (reminderEnabled && (now - session.lastReminderMs()) > reminderIntervalMs) {
                    player.spigot().sendMessage(
                        net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        new net.md_5.bungee.api.chat.TextComponent(
                            "§e正在与 " + session.npcBrainName() + " 对话中..."
                        )
                    );
                    // 更新提醒时间
                    sessions.put(playerId, session.updateReminderTime(now));
                }

                return false;
            });

        }, 60L, 60L); // delay=60ticks(3s), period=60ticks(3s)
    }

    /**
     * 判断玩家是否跨越了整数方块边界。
     * 避免每帧微小移动都触发距离计算，减少 CPU 开销。
     */
    private boolean hasCrossedBlockBoundary(PlayerMoveEvent event) {
        var from = event.getFrom();
        var to   = event.getTo();
        if (to == null) return false;
        return from.getBlockX() != to.getBlockX()
            || from.getBlockY() != to.getBlockY()
            || from.getBlockZ() != to.getBlockZ();
    }

    /**
     * 将任务提交到 Bukkit 主线程执行。
     */
    private void syncToMain(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}
