package com.agenticnpc.audit;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.logging.Logger;

/**
 * MySQL 审计日志双写实现。
 * 复用 MySQL 连接池配置，不新建连接池。
 */
public class MySQLAuditRepository implements AuditRepository {

    private final HikariDataSource dataSource;
    private final Logger           logger;

    public MySQLAuditRepository(HikariDataSource sharedDataSource, Logger logger) {
        this.dataSource = sharedDataSource;
        this.logger     = logger;
        initSchema();
    }

    private void initSchema() {
        String sql = """
            CREATE TABLE IF NOT EXISTS audit_log (
                id            BIGINT PRIMARY KEY AUTO_INCREMENT,
                timestamp_ms  BIGINT  NOT NULL,
                player_uuid   VARCHAR(36) NOT NULL,
                player_name   VARCHAR(64) NOT NULL,
                brain_id      VARCHAR(64) NOT NULL,
                event_type    VARCHAR(32) NOT NULL,
                input         TEXT,
                output        TEXT,
                action_type   VARCHAR(32),
                action_params TEXT,
                server_id     VARCHAR(64) NOT NULL DEFAULT 'default',
                INDEX idx_player (player_uuid, timestamp_ms),
                INDEX idx_time   (timestamp_ms)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            logger.warning("[审计] MySQL Schema 初始化失败: " + e.getMessage());
        }
    }

    @Override
    public void save(AuditLogger.AuditEntry entry) {
        String sql = """
            INSERT INTO audit_log
              (timestamp_ms, player_uuid, player_name, brain_id,
               event_type, input, output, action_type, action_params, server_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1,   entry.timestampMs());
            ps.setString(2, entry.playerUuid());
            ps.setString(3, entry.playerName());
            ps.setString(4, entry.brainId());
            ps.setString(5, entry.eventType().name());
            ps.setString(6, entry.input());
            ps.setString(7, entry.output());
            ps.setString(8, entry.actionType() != null ? entry.actionType().name() : null);
            ps.setString(9, entry.actionParams());
            ps.setString(10, entry.serverId());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[审计] MySQL 写入失败: " + e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        // 连接池由外部管理，此处不关闭
    }
}
