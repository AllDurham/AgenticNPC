package com.agenticnpc.console;

import com.agenticnpc.audit.TokenTracker;
import com.agenticnpc.command.HealthCommand;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.dispatch.CircuitBreaker;
import com.agenticnpc.dispatch.RedisRateLimiter;
import com.agenticnpc.dispatch.RateLimiter;
import com.agenticnpc.hook.ChatCollector;
import com.agenticnpc.memory.MemoryRepository;
import com.agenticnpc.memory.MySQLRepository;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Web 运维控制台。
 *
 * 架构约束：
 * - 默认仅监听 127.0.0.1（不允许 0.0.0.0 作为默认值）
 * - 必须 Bearer Token 认证
 * - 启动失败不崩插件
 * - Web server 独立生命周期
 * - 不阻塞 Bukkit 主线程
 */
public class WebConsole {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ConfigManager          config;
    private final MetricsCollector       metricsCollector;
    private final RecentInteractionStore interactionStore;
    private final PromptSnapshotStore    promptStore;
    private final ChatCollector          chatCollector;
    private final CircuitBreaker         circuitBreaker;
    private final RateLimiter            rateLimiter;
    private final TokenTracker           tokenTracker;
    private final HealthCommand          healthCommand;
    private final MemoryRepository       memoryRepository;
    private final Logger                 logger;

    private Javalin app;
    private volatile boolean running = false;

    public WebConsole(ConfigManager config,
                      MetricsCollector metricsCollector,
                      RecentInteractionStore interactionStore,
                      PromptSnapshotStore promptStore,
                      ChatCollector chatCollector,
                      CircuitBreaker circuitBreaker,
                      RateLimiter rateLimiter,
                      TokenTracker tokenTracker,
                      HealthCommand healthCommand,
                      MemoryRepository memoryRepository,
                      Logger logger) {
        this.config             = config;
        this.metricsCollector   = metricsCollector;
        this.interactionStore   = interactionStore;
        this.promptStore        = promptStore;
        this.chatCollector      = chatCollector;
        this.circuitBreaker     = circuitBreaker;
        this.rateLimiter        = rateLimiter;
        this.tokenTracker       = tokenTracker;
        this.healthCommand      = healthCommand;
        this.memoryRepository   = memoryRepository;
        this.logger             = logger;
    }

    /**
     * 启动 Web 服务（非阻塞）。
     * 失败时仅 WARN 日志，不影响主插件。
     */
    public void start() {
        if (!config.isWebConsoleEnabled()) {
            logger.info("[Console] Web 控制台未启用（web-console.enabled=false）");
            return;
        }

        String host = config.getWebConsoleHost();
        int    port = config.getWebConsolePort();
        String authToken = config.getWebConsoleAuthToken();

        if (authToken == null || authToken.isBlank() || "change-me".equals(authToken)) {
            logger.warning("[Console] Web 控制台 auth-token 未设置或为默认值，请修改！");
            logger.warning("[Console] 安全要求：必须设置有效的 auth-token 才能启动控制台");
            return;
        }

        try {
            app = Javalin.create(javalinConfig -> {
                javalinConfig.showJavalinBanner = false;
                javalinConfig.jsonMapper(new GsonJsonMapper());
            });

            // ---- Auth 中间件 ----
            app.before(ctx -> {
                String path = ctx.path();

                // 登录页面本身不需要认证
                if (path.equals("/console/login")) return;

                // 检查 Authorization header（API 调用）或 cookie（页面导航）
                String auth = ctx.header("Authorization");
                String cookieToken = ctx.cookie("anpc_token");
                boolean authorized = (auth != null && auth.equals("Bearer " + authToken))
                    || authToken.equals(cookieToken);

                if (!authorized) {
                    if (path.startsWith("/api/")) {
                        ctx.status(401).result("{\"error\":\"Unauthorized\"}");
                    } else {
                        ctx.redirect("/console/login");
                    }
                }
            });

            // ---- 路由 ----
            registerRoutes();

            // ---- 启动 ----
            app.start(host, port);
            running = true;
            logger.info("[Console] Web 控制台已启动 | 地址: http://" + host + ":" + port);

        } catch (Exception e) {
            logger.warning("[Console] Web 控制台启动失败: " + e.getMessage());
            logger.warning("[Console] 主插件不受影响");
            running = false;
        }
    }

    private void registerRoutes() {
        // ---- HTML 页面 ----
        app.get("/console",        ctx -> serveHtml(ctx, "console/dashboard.html"));
        app.get("/console/prompts", ctx -> serveHtml(ctx, "console/prompts.html"));
        app.get("/console/login",  ctx -> serveHtml(ctx, "console/login.html"));

        // ---- API: 综合 Dashboard 数据 ----
        app.get("/api/dashboard", ctx -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("serverId", config.getServerId());
            data.put("circuitBreakerState", circuitBreaker.getState().name());
            data.put("activeSessions", chatCollector.getActiveSessionCount());
            data.put("processingCount", chatCollector.getProcessingCount());
            data.put("queueTasks", 0); // 未来扩展
            data.put("redisStatus", getRedisStatus());
            data.put("memoryBackend", memoryRepository instanceof MySQLRepository ? "MySQL" : "SQLite");
            data.put("semanticGuardEnabled", config.isSemanticGuardEnabled());
            data.put("metrics", metricsCollector.snapshot());

            // 今日 Token 统计
            long todayStart = LocalDateStartMs();
            if (tokenTracker != null) {
                var stats = tokenTracker.queryGlobal(todayStart);
                data.put("todayTokens", stats.totalTokens());
                data.put("todayRequests", stats.requestCount());
            } else {
                data.put("todayTokens", 0);
                data.put("todayRequests", 0);
            }

            // 最近对话
            data.put("recentInteractions", interactionStore.getRecent(20));

            ctx.json(data);
        });

        // ---- API: 指标数据 ----
        app.get("/api/metrics", ctx -> ctx.json(metricsCollector.snapshot()));

        // ---- API: 最近对话 ----
        app.get("/api/interactions", ctx -> {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
            ctx.json(Map.of("interactions", interactionStore.getRecent(limit)));
        });

        // ---- API: Prompt 快照 ----
        app.get("/api/prompts", ctx -> {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
            ctx.json(Map.of("snapshots", promptStore.getRecent(limit)));
        });
    }

    private void serveHtml(Context ctx, String resourcePath) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                ctx.status(404).result("Not Found");
                return;
            }
            String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            ctx.contentType("text/html; charset=UTF-8").result(html);
        } catch (Exception e) {
            ctx.status(500).result("Internal Error");
        }
    }

    private String getRedisStatus() {
        if (rateLimiter instanceof RedisRateLimiter redis) {
            try {
                return redis.ping() ? "PONG" : "DEGRADED";
            } catch (Exception e) {
                return "ERROR";
            }
        }
        return "N/A";
    }

    /**
     * 今日 0 点的毫秒时间戳。
     */
    private long LocalDateStartMs() {
        return LocalDateTime.now()
            .toLocalDate()
            .atStartOfDay()
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli();
    }

    /**
     * 停止 Web 服务。
     */
    public void stop() {
        if (app != null && running) {
            try {
                app.stop();
                logger.info("[Console] Web 控制台已关闭");
            } catch (Exception e) {
                logger.warning("[Console] Web 控制台关闭异常: " + e.getMessage());
            }
            running = false;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
