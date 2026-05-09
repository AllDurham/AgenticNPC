package com.agenticnpc.memory;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.model.ChatMessage;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQLite 持久化实现（默认后端）。
 *
 * 特性：
 * - 嵌入式，零外部依赖配置
 * - 数据库文件自动创建在插件目录下
 * - 连接池最大连接数限 1（SQLite 单写者模型）
 * - 使用 WAL 模式提升并发读性能
 */
public class SQLiteRepository implements MemoryRepository {

    private final HikariDataSource dataSource;
    private final Logger           logger;

    public SQLiteRepository(Plugin plugin, ConfigManager config, Logger logger) {
        this.logger = logger;

        File dbFile = new File(plugin.getDataFolder(), config.getSqliteFile());

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(5000);
        hikariConfig.setPoolName("AgenticNPC-SQLite");
        // SQLite 性能优化参数
        hikariConfig.addDataSourceProperty("journal_mode", "WAL");
        hikariConfig.addDataSourceProperty("synchronous",  "NORMAL");

        this.dataSource = new HikariDataSource(hikariConfig);
        initSchema();
        logger.info("[数据库] SQLite 初始化完成 | 文件: " + dbFile.getAbsolutePath());
    }

    private void initSchema() {
        try (Connection conn = dataSource.getConnection();
             Statement  stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chat_history (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid TEXT    NOT NULL,
                    brain_id    TEXT    NOT NULL,
                    role        TEXT    NOT NULL,
                    content     TEXT    NOT NULL,
                    created_at  INTEGER NOT NULL
                )
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_player_brain_time
                ON chat_history (player_uuid, brain_id, created_at)
                """);

            // ---- 审计日志表 ----
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp_ms INTEGER NOT NULL,
                    player_uuid  TEXT    NOT NULL,
                    player_name  TEXT    NOT NULL,
                    brain_id     TEXT    NOT NULL,
                    event_type   TEXT    NOT NULL,
                    input        TEXT,
                    output       TEXT,
                    action_type  TEXT,
                    action_params TEXT,
                    server_id    TEXT    NOT NULL DEFAULT 'default'
                )
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_audit_player
                ON audit_log (player_uuid, timestamp_ms)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_audit_time
                ON audit_log (timestamp_ms)
                """);

            // ---- Token 统计表 ----
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS token_usage (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp_ms     INTEGER NOT NULL,
                    player_uuid      TEXT    NOT NULL,
                    player_name      TEXT    NOT NULL,
                    brain_id         TEXT    NOT NULL,
                    prompt_tokens    INTEGER NOT NULL DEFAULT 0,
                    completion_tokens INTEGER NOT NULL DEFAULT 0,
                    total_tokens     INTEGER NOT NULL DEFAULT 0,
                    server_id        TEXT    NOT NULL DEFAULT 'default'
                )
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_token_player
                ON token_usage (player_uuid, timestamp_ms)
                """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_token_brain
                ON token_usage (brain_id, timestamp_ms)
                """);

        } catch (SQLException e) {
            throw new RuntimeException("[数据库] SQLite Schema 初始化失败", e);
        }
    }

    /**
     * 供 TokenTracker / AuditLogger 获取数据库连接。
     */
    public java.sql.Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public Deque<ChatMessage> loadRecent(UUID playerId, String brainId, int limit) {
        String sql = """
            SELECT role, content, created_at
            FROM chat_history
            WHERE player_uuid = ? AND brain_id = ?
            ORDER BY created_at DESC
            LIMIT ?
            """;

        // 使用 ArrayDeque 作为结果容器
        ArrayDeque<ChatMessage> result = new ArrayDeque<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, playerId.toString());
            ps.setString(2, brainId);
            ps.setInt(3, limit * 2); // user + assistant 各算一条

            ResultSet rs = ps.executeQuery();
            // 查询结果是倒序的，addFirst 恢复正序
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
                // 使用自增时间戳保证顺序（同一毫秒内多条消息）
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

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("[数据库] SQLite 连接池已关闭");
        }
    }
}
