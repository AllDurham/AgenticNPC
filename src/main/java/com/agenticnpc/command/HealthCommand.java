package com.agenticnpc.command;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.dispatch.CircuitBreaker;
import com.agenticnpc.dispatch.RateLimiter;
import com.agenticnpc.dispatch.RedisRateLimiter;
import com.agenticnpc.memory.MemoryRepository;
import com.agenticnpc.memory.MySQLRepository;
import com.agenticnpc.memory.SQLiteRepository;
import org.bukkit.command.CommandSender;

import java.util.concurrent.atomic.AtomicLong;

/**
 * /anpc health 命令：显示运行状态观测信息。
 *
 * 输出：
 * - LLM 熔断状态
 * - 限流后端 + Redis 状态
 * - 今日 Token 消耗
 * - Memory 后端
 * - SemanticGuard 状态
 */
public class HealthCommand {

    private final CircuitBreaker    circuitBreaker;
    private final RateLimiter       rateLimiter;
    private final ConfigManager     config;
    private final MemoryRepository  memoryRepository;
    private final com.agenticnpc.audit.TokenTracker tokenTracker;

    // 可选：SemanticGuard 引用（延迟设置）
    private volatile boolean semanticGuardEnabled = false;

    // 简易错误计数器（最近 60 秒）
    private final AtomicLong recentErrors = new AtomicLong(0);
    private volatile long    lastErrorResetMs = System.currentTimeMillis();

    public HealthCommand(CircuitBreaker circuitBreaker,
                         RateLimiter rateLimiter,
                         ConfigManager config,
                         MemoryRepository memoryRepository,
                         com.agenticnpc.audit.TokenTracker tokenTracker) {
        this.circuitBreaker   = circuitBreaker;
        this.rateLimiter      = rateLimiter;
        this.config           = config;
        this.memoryRepository = memoryRepository;
        this.tokenTracker     = tokenTracker;
    }

    public void setSemanticGuardEnabled(boolean enabled) {
        this.semanticGuardEnabled = enabled;
    }

    /** 记录一次错误（供外部调用） */
    public void recordError() {
        resetErrorWindowIfNeeded();
        recentErrors.incrementAndGet();
    }

    public void handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("agenticnpc.admin")) {
            sender.sendMessage("§c你没有权限使用此命令。");
            return;
        }

        sender.sendMessage("§e========= AgenticNPC Health =========");

        // 1. 熔断状态
        CircuitBreaker.State cbState = circuitBreaker.getState();
        String cbColor = switch (cbState) {
            case CLOSED    -> "§a";
            case HALF_OPEN -> "§e";
            case OPEN      -> "§c";
        };
        sender.sendMessage("§fLLM 熔断器:      " + cbColor + cbState.name());

        // 2. 限流后端
        String rlBackend = config.getRateLimitBackend();
        sender.sendMessage("§f限流后端:        §a" + rlBackend);
        if ("redis".equalsIgnoreCase(rlBackend) && rateLimiter instanceof RedisRateLimiter redis) {
            boolean redisOk = redis.ping();
            sender.sendMessage("§fRedis 状态:      " + (redisOk ? "§aPONG" : "§cDEGRADED"));
        }

        // 3. 今日 Token 消耗
        if (tokenTracker != null) {
            long todayStart = getTodayStartMs();
            var stats = tokenTracker.queryGlobal(todayStart);
            sender.sendMessage(String.format(
                "§f今日 Token:      §a%d §7(请求 %d 次)", stats.totalTokens(), stats.requestCount()));
        }

        // 4. Memory 后端
        String memBackend = memoryRepository instanceof MySQLRepository ? "MySQL" : "SQLite";
        sender.sendMessage("§fMemory 后端:     §a" + memBackend);

        // 5. SemanticGuard
        sender.sendMessage("§fSemanticGuard:   " + (semanticGuardEnabled ? "§a启用" : "§7禁用"));

        // 6. 最近错误数
        resetErrorWindowIfNeeded();
        long errors = recentErrors.get();
        String errColor = errors == 0 ? "§a" : (errors < 5 ? "§e" : "§c");
        sender.sendMessage("§f最近错误(60s):   " + errColor + errors);

        // 7. 服务器 ID
        sender.sendMessage("§f服务器 ID:       §a" + config.getServerId());

        sender.sendMessage("§e=====================================");
    }

    private long getTodayStartMs() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private void resetErrorWindowIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastErrorResetMs > 60_000) {
            recentErrors.set(0);
            lastErrorResetMs = now;
        }
    }
}
