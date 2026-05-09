package com.agenticnpc.audit;

import com.agenticnpc.config.ConfigManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Token 用量统计系统。
 *
 * 职责：
 * 1. 从 LLM API 响应中提取 usage 字段
 * 2. 异步写入数据库
 * 3. 支持按全局 / 玩家 / Brain 三个维度查询
 */
public class TokenTracker {

    public record TokenUsage(
        long   timestampMs,
        String playerUuid,
        String playerName,
        String brainId,
        int    promptTokens,
        int    completionTokens,
        int    totalTokens,
        String serverId
    ) {}

    public record StatsResult(
        long totalTokens,
        long promptTokens,
        long completionTokens,
        long requestCount
    ) {}

    private final BlockingQueue<TokenUsage> queue   = new LinkedBlockingQueue<>(5000);
    private final Thread                    consumer;
    private volatile boolean                running = true;

    private final ConfigManager config;
    private final Logger        logger;
    private Supplier<Connection> connSupplier;

    public TokenTracker(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;

        this.consumer = new Thread(this::consumeLoop, "AgenticNPC-TokenTracker");
        this.consumer.setDaemon(true);
        this.consumer.start();
    }

    public void setDbConnection(Supplier<Connection> connSupplier) {
        this.connSupplier = connSupplier;
    }

    /**
     * 从 LLM 完整响应体中提取 usage 并记录。
     */
    public void track(String fullResponseBody, UUID playerId,
                      String playerName, String brainId) {
        try {
            JsonObject root  = JsonParser.parseString(fullResponseBody).getAsJsonObject();
            JsonObject usage = root.getAsJsonObject("usage");
            if (usage == null) return;

            int prompt     = usage.get("prompt_tokens").getAsInt();
            int completion = usage.get("completion_tokens").getAsInt();
            int total      = usage.get("total_tokens").getAsInt();

            TokenUsage tokenUsage = new TokenUsage(
                System.currentTimeMillis(),
                playerId.toString(), playerName, brainId,
                prompt, completion, total,
                config.getServerId()
            );

            if (!queue.offer(tokenUsage)) {
                logger.warning("[Token] 统计队列已满，丢弃本次记录");
            }

            checkAlert(playerName, brainId, total);

        } catch (Exception e) {
            logger.warning("[Token] 解析 usage 失败: " + e.getMessage());
        }
    }

    private void checkAlert(String playerName, String brainId, int totalTokens) {
        int threshold = config.getTokenAlertThreshold();
        if (threshold > 0 && totalTokens > threshold) {
            logger.warning(String.format(
                "[Token][告警] 单次请求 Token 超限！| 玩家: %s | Brain: %s | 消耗: %d > 阈值: %d",
                playerName, brainId, totalTokens, threshold
            ));
        }
    }

    // ---- 查询接口 ----

    public StatsResult queryGlobal(long sinceMs) {
        return queryStats(
            "SELECT COALESCE(SUM(total_tokens),0), COALESCE(SUM(prompt_tokens),0), " +
            "COALESCE(SUM(completion_tokens),0), COUNT(*) FROM token_usage WHERE timestamp_ms > ?",
            sinceMs
        );
    }

    public StatsResult queryByPlayer(UUID playerId, long sinceMs) {
        return queryStats(
            "SELECT COALESCE(SUM(total_tokens),0), COALESCE(SUM(prompt_tokens),0), " +
            "COALESCE(SUM(completion_tokens),0), COUNT(*) FROM token_usage " +
            "WHERE player_uuid = ? AND timestamp_ms > ?",
            playerId.toString(), sinceMs
        );
    }

    public StatsResult queryByBrain(String brainId, long sinceMs) {
        return queryStats(
            "SELECT COALESCE(SUM(total_tokens),0), COALESCE(SUM(prompt_tokens),0), " +
            "COALESCE(SUM(completion_tokens),0), COUNT(*) FROM token_usage " +
            "WHERE brain_id = ? AND timestamp_ms > ?",
            brainId, sinceMs
        );
    }

    private StatsResult queryStats(String sql, Object... params) {
        if (connSupplier == null) return new StatsResult(0, 0, 0, 0);
        try (Connection conn = connSupplier.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new StatsResult(
                    rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)
                );
            }
        } catch (SQLException e) {
            logger.warning("[Token] 查询失败: " + e.getMessage());
        }
        return new StatsResult(0, 0, 0, 0);
    }

    // ---- 内部 ----

    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                TokenUsage usage = queue.poll(1, TimeUnit.SECONDS);
                if (usage == null || connSupplier == null) continue;

                String sql = """
                    INSERT INTO token_usage
                      (timestamp_ms, player_uuid, player_name, brain_id,
                       prompt_tokens, completion_tokens, total_tokens, server_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;

                try (Connection conn = connSupplier.get();
                     PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setLong(1,   usage.timestampMs());
                    ps.setString(2, usage.playerUuid());
                    ps.setString(3, usage.playerName());
                    ps.setString(4, usage.brainId());
                    ps.setInt(5,    usage.promptTokens());
                    ps.setInt(6,    usage.completionTokens());
                    ps.setInt(7,    usage.totalTokens());
                    ps.setString(8, usage.serverId());
                    ps.executeUpdate();
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.warning("[Token] 写入失败: " + e.getMessage());
            }
        }
    }

    public void shutdown() {
        running = false;
        consumer.interrupt();
        try { consumer.join(3000); } catch (InterruptedException ignored) {}
    }
}
