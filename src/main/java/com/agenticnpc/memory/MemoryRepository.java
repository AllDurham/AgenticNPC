package com.agenticnpc.memory;

import com.agenticnpc.model.ChatMessage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * 对话历史持久化接口。
 * 支持 SQLite（默认）和 MySQL（可选）两种实现。
 */
public interface MemoryRepository {

    /**
     * 加载指定玩家与 NPC 最近 N 条对话历史。
     * 返回结果按时间正序排列（最旧在队列头部）。
     */
    Deque<ChatMessage> loadRecent(UUID playerId, String brainId, int limit);

    /**
     * 保存对话历史（全量替换策略）。
     */
    void save(UUID playerId, String brainId, Deque<ChatMessage> history);

    /**
     * 删除指定玩家的所有历史（GDPR / 隐私清理用）。
     */
    void deleteAll(UUID playerId);

    /**
     * 关闭数据库连接池（插件卸载时调用）。
     */
    void shutdown();
}
