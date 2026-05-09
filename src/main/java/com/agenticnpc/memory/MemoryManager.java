package com.agenticnpc.memory;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.model.ChatMessage;
import com.google.common.cache.*;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 会话记忆管理器（双层存储）。
 *
 * L1: Guava Cache 热缓存（内存，TTL 驱逐）
 * L2: MemoryRepository 持久层（SQLite / MySQL）
 *
 * Key 结构: "playerUUID::brainId"
 * 同一玩家与不同 NPC 的记忆相互独立。
 */
public class MemoryManager {

    private final Cache<String, Deque<ChatMessage>> hotCache;
    private final MemoryRepository                  repository;
    private final ConfigManager                     config;
    private final Logger                            logger;

    public MemoryManager(MemoryRepository repository, ConfigManager config, Logger logger) {
        this.repository = repository;
        this.config     = config;
        this.logger     = logger;

        this.hotCache = CacheBuilder.newBuilder()
            .expireAfterAccess(config.getMemoryTtlMinutes(), TimeUnit.MINUTES)
            .maximumSize(config.getMemoryMaxSessions())
            .removalListener(this::onEviction)
            .build();
    }

    /**
     * 加载玩家与指定 NPC 的对话历史。
     * 热缓存优先，未命中则从持久层加载。
     */
    public Deque<ChatMessage> loadHistory(UUID playerId, String brainId) {
        String cacheKey = buildKey(playerId, brainId);
        Deque<ChatMessage> cached = hotCache.getIfPresent(cacheKey);

        if (cached != null) {
            if (config.isDebugMode()) {
                logger.info("[Memory] 热缓存命中 | Key: " + cacheKey);
            }
            return cached;
        }

        // 缓存未命中：从持久层加载
        Deque<ChatMessage> loaded = repository.loadRecent(
            playerId, brainId, config.getMaxHistoryPerSession()
        );

        // 持久层没有记录时返回空队列（不是 null）
        if (loaded == null) loaded = new ArrayDeque<>();

        hotCache.put(cacheKey, loaded);

        if (config.isDebugMode()) {
            logger.info("[Memory] 从持久层加载 | Key: " + cacheKey
                + " | 条数: " + loaded.size());
        }

        return loaded;
    }

    /**
     * 追加新一轮对话（user + assistant）并持久化。
     * 必须在异步线程调用。
     *
     * @param playerId      玩家 UUID
     * @param brainId       Brain ID
     * @param userMessage   玩家输入
     * @param assistantMsg  NPC 回复
     */
    public void appendAndPersist(UUID playerId, String brainId,
                                 ChatMessage userMessage, ChatMessage assistantMsg) {
        String cacheKey = buildKey(playerId, brainId);
        Deque<ChatMessage> history = loadHistory(playerId, brainId);

        history.addLast(userMessage);
        history.addLast(assistantMsg);

        // 滑动窗口：超出最大条数时移除最旧消息
        // maxHistory * 2 是因为每轮对话有 user + assistant 两条
        int maxMessages = config.getMaxHistoryPerSession() * 2;
        while (history.size() > maxMessages) {
            history.pollFirst();
        }

        // 更新热缓存
        hotCache.put(cacheKey, history);

        // 异步持久化（调用方已在异步线程）
        repository.save(playerId, brainId, history);

        if (config.isDebugMode()) {
            logger.info("[Memory] 已追加并持久化 | Key: " + cacheKey
                + " | 当前条数: " + history.size());
        }
    }

    /**
     * 玩家退出时主动持久化并清理热缓存。
     * 在 PlayerQuitEvent 中调用（异步执行）。
     */
    public void flushAndEvict(UUID playerId) {
        String prefix = playerId.toString() + "::";

        hotCache.asMap().entrySet().stream()
            .filter(e -> e.getKey().startsWith(prefix))
            .forEach(e -> {
                String[] parts  = e.getKey().split("::", 2);
                UUID     pId    = UUID.fromString(parts[0]);
                String   bId    = parts[1];
                repository.save(pId, bId, e.getValue());
            });

        // 批量清理该玩家的所有缓存条目
        hotCache.asMap().keySet()
            .removeIf(key -> key.startsWith(prefix));

        if (config.isDebugMode()) {
            logger.info("[Memory] 玩家退出，缓存已清理 | UUID: " + playerId);
        }
    }

    /**
     * 缓存驱逐监听器。
     * 超时驱逐时执行最后一次持久化（防止数据丢失）。
     */
    private void onEviction(RemovalNotification<String, Deque<ChatMessage>> notification) {
        // 仅处理超时驱逐（SIZE 驱逐的数据已在 append 时持久化）
        if (notification.getCause() != RemovalCause.EXPIRED) return;

        String key = notification.getKey();
        if (key == null) return;

        String[] parts = key.split("::", 2);
        if (parts.length != 2) return;

        try {
            UUID   playerId = UUID.fromString(parts[0]);
            String brainId  = parts[1];
            repository.save(playerId, brainId, notification.getValue());

            if (config.isDebugMode()) {
                logger.info("[Memory] TTL 驱逐持久化 | Key: " + key);
            }
        } catch (Exception e) {
            logger.warning("[Memory] TTL 驱逐持久化失败 | Key: " + key
                + " | 错误: " + e.getMessage());
        }
    }

    /** 关闭时持久化所有热缓存数据。 */
    public void shutdown() {
        logger.info("[Memory] 正在持久化所有热缓存...");
        hotCache.asMap().forEach((key, history) -> {
            String[] parts = key.split("::", 2);
            if (parts.length == 2) {
                try {
                    repository.save(UUID.fromString(parts[0]), parts[1], history);
                } catch (Exception e) {
                    logger.warning("[Memory] 关闭持久化失败 | Key: " + key);
                }
            }
        });
        hotCache.invalidateAll();
        repository.shutdown();
        logger.info("[Memory] 记忆系统已关闭");
    }

    private String buildKey(UUID playerId, String brainId) {
        return playerId.toString() + "::" + brainId;
    }
}
