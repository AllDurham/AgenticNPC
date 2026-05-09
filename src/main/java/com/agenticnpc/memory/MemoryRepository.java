package com.agenticnpc.memory;

import com.agenticnpc.model.ChatMessage;

import java.util.Deque;
import java.util.Optional;
import java.util.UUID;

/**
 * 对话历史持久化接口。
 * 支持 SQLite（默认）和 MySQL（可选）两种实现。
 */
public interface MemoryRepository {

    // ---- 对话历史 ----
    Deque<ChatMessage> loadRecent(UUID playerId, String brainId, int limit);
    void save(UUID playerId, String brainId, Deque<ChatMessage> history);
    void deleteAll(UUID playerId);

    // ---- 对话摘要 ----
    Optional<String> loadSummary(UUID playerId, String brainId);
    void saveSummary(UUID playerId, String brainId, String summary,
                     int tokenCount, int roundCount);
    void deleteSummary(UUID playerId, String brainId);

    // ---- 永久画像 ----
    Optional<String> loadProfile(UUID playerId, String scopeId, String scopeType);
    void saveProfile(UUID playerId, String scopeId, String scopeType, String profile);
    void deleteProfile(UUID playerId, String scopeId, String scopeType);

    // ---- 情绪值 ----
    String loadEmotion(String keyId, String brainId);
    void saveEmotion(String keyId, String brainId, String emotionLevel);

    void shutdown();
}
