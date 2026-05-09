package com.agenticnpc.emotion;

import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.memory.MemoryRepository;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * NPC 情绪管理器。
 *
 * 情绪值存储维度：
 * - emotion-shared=false（默认）：Key = playerUUID::brainId（每人独立）
 * - emotion-shared=true：Key = GLOBAL::brainId（全体共享）
 */
public class EmotionManager {

    private final ConcurrentHashMap<String, EmotionLevel> cache = new ConcurrentHashMap<>();
    private final MemoryRepository repository;
    private final ConfigManager    config;
    private final Logger           logger;

    public EmotionManager(MemoryRepository repository, ConfigManager config, Logger logger) {
        this.repository = repository;
        this.config     = config;
        this.logger     = logger;
    }

    public EmotionLevel getEmotion(UUID playerId, BrainConfig brain) {
        String key = buildKey(playerId, brain);
        return cache.computeIfAbsent(key, k -> {
            String stored = repository.loadEmotion(resolvePlayerId(playerId, brain), brain.id());
            return EmotionLevel.fromString(stored != null ? stored : brain.defaultEmotion());
        });
    }

    public void applyChange(UUID playerId, BrainConfig brain, EmotionChange change) {
        if (change == null || change == EmotionChange.NONE) return;

        String       key     = buildKey(playerId, brain);
        EmotionLevel current = getEmotion(playerId, brain);
        EmotionLevel updated = switch (change) {
            case UPGRADE   -> current.upgrade();
            case DOWNGRADE -> current.downgrade();
            default        -> current;
        };

        if (updated == current) return;

        cache.put(key, updated);
        repository.saveEmotion(resolvePlayerId(playerId, brain), brain.id(), updated.name());

        logger.info(String.format("[情绪] 情绪变化 | Brain: %s | 玩家: %s | %s -> %s",
            brain.id(), playerId, current.displayName, updated.displayName));
    }

    public void setEmotion(UUID playerId, BrainConfig brain, EmotionLevel level) {
        String key = buildKey(playerId, brain);
        cache.put(key, level);
        repository.saveEmotion(resolvePlayerId(playerId, brain), brain.id(), level.name());
    }

    private String buildKey(UUID playerId, BrainConfig brain) {
        return resolvePlayerId(playerId, brain) + "::" + brain.id();
    }

    private String resolvePlayerId(UUID playerId, BrainConfig brain) {
        return brain.emotionShared() ? "GLOBAL" : playerId.toString();
    }
}
