package com.agenticnpc.memory;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.model.ChatMessage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * MySQL 持久化实现。
 *
 * 实现与 SQLiteRepository 完全对等的持久化功能。
 * - 使用 HikariCP 连接池
 * - 所有 SQL 异常被捕获并记录 SEVERE 日志，不向上抛出
 * - 查询类方法异常时返回空结果
 * - 写入类方法异常时静默失败，仅记录日志
 * - MySQL 连接失败时 fail-fast（构造函数抛出异常，主类捕获后 disable 插件）
 * - emotion-shared 的玩家标识使用 "GLOBAL"（与 SQLite 实现一致）
 * - 字符集 utf8mb4
 */
public class MySQLRepository implements MemoryRepository {

    private final HikariDataSource dataSource;
    private final Logger           logger;

    public MySQLRepository(ConfigManager config, Logger logger) {
        this.logger = logger;

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(String.format(
            "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8mb4&serverTimezone=UTC",
            config.getMysqlHost(),
            config.getMysqlPort(),
            config.getMysqlDatabase()
        ));
        hikariConfig.setUsername(config.getMysqlUser());
        hikariConfig.setPassword(config.getMysqlPassword());
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setPoolName("AgenticNPC-MySQL");
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // fail-fast: 连接池初始化时即验证连接
        hikariConfig.setInitializationFailTimeout(0);

        this.dataSource = new HikariDataSource(hikariConfig);
        initSchema();
        logger.info("[数据库] MySQL 初始化完成 | 主机: " + config.getMysqlHost()
            + ":" + config.getMysqlPort() + " | 库: " + config.getMysqlDatabase());
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chat_history (
                    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
                    player_uuid VARCHAR(36)  NOT NULL,
                    brain_id    VARCHAR(64)  NOT NULL,
                    role        VARCHAR(16)  NOT NULL,
                    content     MEDIUMTEXT   NOT NULL,
                    created_at  BIGINT       NOT NULL,
                    INDEX idx_player_brain_time (player_uuid, brain_id, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS conversation_summary (
                    player_uuid VARCHAR(36)  NOT NULL,
                    brain_id    VARCHAR(64)  NOT NULL,
                    summary     MEDIUMTEXT   NOT NULL,
                    token_count INT          NOT NULL DEFAULT 0,
                    round_count INT          NOT NULL DEFAULT 0,
                    updated_at  BIGINT       NOT NULL,
                    PRIMARY KEY (player_uuid, brain_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_profile (
                    player_uuid VARCHAR(36)  NOT NULL,
                    scope_id    VARCHAR(64)  NOT NULL,
                    scope_type  VARCHAR(16)  NOT NULL,
                    profile     MEDIUMTEXT   NOT NULL,
                    token_count INT          NOT NULL DEFAULT 0,
                    created_at  BIGINT       NOT NULL,
                    updated_at  BIGINT       NOT NULL,
                    PRIMARY KEY (player_uuid, scope_id, scope_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS npc_emotion (
                    player_uuid  VARCHAR(64) NOT NULL,
                    brain_id     VARCHAR(64) NOT NULL,
                    emotion_level VARCHAR(32) NOT NULL DEFAULT 'NEUTRAL',
                    updated_at   BIGINT      NOT NULL,
                    PRIMARY KEY (player_uuid, brain_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);

        } catch (SQLException e) {
            throw new RuntimeException("[数据库] MySQL Schema 初始化失败", e);
        }
    }

    /**
     * 供 TokenTracker / AuditLogger 获取数据库连接。
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // ---- 对话历史 ----

    @Override
    public Deque<ChatMessage> loadRecent(UUID playerId, String brainId, int limit) {
        String sql = """
            SELECT role, content, created_at
            FROM chat_history
            WHERE player_uuid = ? AND brain_id = ?
            ORDER BY created_at DESC
            LIMIT ?
            """;

        ArrayDeque<ChatMessage> result = new ArrayDeque<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, playerId.toString());
            ps.setString(2, brainId);
            ps.setInt(3, limit * 2);

            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.addFirst(new ChatMessage(
                    rs.getString("role"),
                    rs.getString("content"),
                    rs.getLong("created_at")
                ));
            }

        } catch (SQLException e) {
            logger.warning("[数据库] 加载历史失败 | 玩家: " + playerId + " | 错误: " + e.getMessage());
        }

        return result;
    }

    @Override
    public void save(UUID playerId, String brainId, Deque<ChatMessage> history) {
        if (history == null || history.isEmpty()) return;

        String deleteSql = "DELETE FROM chat_history WHERE player_uuid = ? AND brain_id = ?";
        String insertSql = """
            INSERT INTO chat_history (player_uuid, brain_id, role, content, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement del = conn.prepareStatement(deleteSql)) {
                del.setString(1, playerId.toString());
                del.setString(2, brainId);
                del.executeUpdate();
            }

            try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                long ts = history.peekFirst() != null
                    ? history.peekFirst().timestampMs()
                    : System.currentTimeMillis();

                for (ChatMessage msg : history) {
                    ins.setString(1, playerId.toString());
                    ins.setString(2, brainId);
                    ins.setString(3, msg.role());
                    ins.setString(4, msg.content());
                    ins.setLong(5, ts++);
                    ins.addBatch();
                }
                ins.executeBatch();
            }

            conn.commit();

        } catch (SQLException e) {
            logger.warning("[数据库] 保存历史失败 | 玩家: " + playerId + " | 错误: " + e.getMessage());
        }
    }

    @Override
    public void deleteAll(UUID playerId) {
        String sql = "DELETE FROM chat_history WHERE player_uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[数据库] 删除历史失败 | 玩家: " + playerId + " | 错误: " + e.getMessage());
        }
    }

    // ---- 对话摘要 ----

    @Override
    public Optional<String> loadSummary(UUID playerId, String brainId) {
        String sql = "SELECT summary FROM conversation_summary WHERE player_uuid = ? AND brain_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, brainId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(rs.getString("summary"));
        } catch (SQLException e) {
            logger.warning("[数据库] 加载摘要失败: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void saveSummary(UUID playerId, String brainId, String summary,
                            int tokenCount, int roundCount) {
        String sql = """
            INSERT INTO conversation_summary
            (player_uuid, brain_id, summary, token_count, round_count, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                summary = VALUES(summary),
                token_count = VALUES(token_count),
                round_count = VALUES(round_count),
                updated_at = VALUES(updated_at)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, brainId);
            ps.setString(3, summary);
            ps.setInt(4, tokenCount);
            ps.setInt(5, roundCount);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[数据库] 保存摘要失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteSummary(UUID playerId, String brainId) {
        String sql = "DELETE FROM conversation_summary WHERE player_uuid = ? AND brain_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, brainId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[数据库] 删除摘要失败: " + e.getMessage());
        }
    }

    // ---- 永久画像 ----

    @Override
    public Optional<String> loadProfile(UUID playerId, String scopeId, String scopeType) {
        String sql = "SELECT profile FROM player_profile WHERE player_uuid = ? AND scope_id = ? AND scope_type = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, scopeId);
            ps.setString(3, scopeType);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return Optional.of(rs.getString("profile"));
        } catch (SQLException e) {
            logger.warning("[数据库] 加载画像失败: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void saveProfile(UUID playerId, String scopeId, String scopeType, String profile) {
        String sql = """
            INSERT INTO player_profile
            (player_uuid, scope_id, scope_type, profile, token_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                profile = VALUES(profile),
                updated_at = VALUES(updated_at)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, playerId.toString());
            ps.setString(2, scopeId);
            ps.setString(3, scopeType);
            ps.setString(4, profile);
            ps.setInt(5, 0);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[数据库] 保存画像失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteProfile(UUID playerId, String scopeId, String scopeType) {
        String sql = "DELETE FROM player_profile WHERE player_uuid = ? AND scope_id = ? AND scope_type = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setString(2, scopeId);
            ps.setString(3, scopeType);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[数据库] 删除画像失败: " + e.getMessage());
        }
    }

    // ---- 情绪值 ----

    @Override
    public String loadEmotion(String keyId, String brainId) {
        String sql = "SELECT emotion_level FROM npc_emotion WHERE player_uuid = ? AND brain_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, keyId);
            ps.setString(2, brainId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("emotion_level");
        } catch (SQLException e) {
            logger.warning("[数据库] 加载情绪失败: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void saveEmotion(String keyId, String brainId, String emotionLevel) {
        String sql = """
            INSERT INTO npc_emotion (player_uuid, brain_id, emotion_level, updated_at)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                emotion_level = VALUES(emotion_level),
                updated_at = VALUES(updated_at)
            """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, keyId);
            ps.setString(2, brainId);
            ps.setString(3, emotionLevel);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("[数据库] 保存情绪失败: " + e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("[数据库] MySQL 连接池已关闭");
        }
    }
}
