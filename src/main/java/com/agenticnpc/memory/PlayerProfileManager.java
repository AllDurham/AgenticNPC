package com.agenticnpc.memory;

import com.agenticnpc.config.ConfigManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 永久玩家画像管理器。
 *
 * 画像特性：
 * 1. 独立于压缩机制，永远不会被自动删除或修改
 * 2. 由管理员手动维护（/anpc profile set/get/clear）
 * 3. 注入 System Prompt，让 NPC 对玩家「有先验了解」
 */
public class PlayerProfileManager {

    private final MemoryRepository repository;
    private final ConfigManager    config;
    private final Logger           logger;
    private final Map<String, Optional<String>> cache = new ConcurrentHashMap<>();

    public PlayerProfileManager(MemoryRepository repository, ConfigManager config, Logger logger) {
        this.repository = repository;
        this.config     = config;
        this.logger     = logger;
    }

    public Optional<String> getProfile(UUID playerId, String brainId) {
        String key = playerId + "::" + brainId;
        return cache.computeIfAbsent(key, k ->
            repository.loadProfile(playerId, brainId, "brain")
        );
    }

    public void setProfile(UUID playerId, String brainId, String profile) {
        int maxTokens = config.getProfileMaxTokens();
        if (estimateTokens(profile) > maxTokens) {
            throw new IllegalArgumentException("画像内容超出 Token 上限（" + maxTokens + "），请精简内容");
        }
        repository.saveProfile(playerId, brainId, "brain", profile);
        cache.put(playerId + "::" + brainId, Optional.of(profile));
        logger.info(String.format("[画像] 更新画像 | 玩家: %s | Brain: %s | 长度: %d chars",
            playerId, brainId, profile.length()));
    }

    public void clearProfile(UUID playerId, String brainId) {
        repository.deleteProfile(playerId, brainId, "brain");
        cache.put(playerId + "::" + brainId, Optional.empty());
    }

    public void invalidateCache() {
        cache.clear();
    }

    private int estimateTokens(String text) {
        int chinese = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (c >= '一' && c <= '鿿') chinese++;
            else other++;
        }
        return (int)(chinese / 1.5 + other / 4.0);
    }
}
