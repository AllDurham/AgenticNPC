package com.agenticnpc.dispatch;

import com.agenticnpc.config.ConfigManager;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Cache;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 本地 JVM 令牌桶限流器（原有实现）。
 * 按「玩家UUID + BrainID」双维度限流。
 */
public class LocalRateLimiter implements RateLimiter {

    private final Cache<String, TokenBucket> buckets;
    private final ConfigManager              config;

    public LocalRateLimiter(ConfigManager config) {
        this.config = config;
        this.buckets = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build();
    }

    @Override
    public RateLimitResult tryAcquire(UUID playerId, String brainId) {
        String key = buildKey(playerId, brainId);

        TokenBucket bucket = buckets.getIfPresent(key);
        if (bucket == null) {
            bucket = new TokenBucket(config.getRateLimitMax(), config.getRateLimitPeriodMs());
            buckets.put(key, bucket);
        }

        if (bucket.tryConsume()) {
            return RateLimitResult.allow();
        }

        long waitSec = bucket.getNextRefillMs() / 1000 + 1;
        return RateLimitResult.reject(
            "对话过于频繁，请等待 " + waitSec + " 秒后再试。"
        );
    }

    private String buildKey(UUID playerId, String brainId) {
        return config.isGlobalRateLimit()
            ? playerId.toString()
            : playerId + "::" + brainId;
    }

    // ================================================================
    // 令牌桶实现
    // ================================================================

    private static class TokenBucket {
        private final int  capacity;
        private final long refillPeriodMs;
        private int        tokens;
        private long       lastRefillMs;

        TokenBucket(int capacity, long refillPeriodMs) {
            this.capacity       = capacity;
            this.refillPeriodMs = refillPeriodMs;
            this.tokens         = capacity;
            this.lastRefillMs   = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            refillIfNeeded();
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }

        synchronized long getNextRefillMs() {
            return Math.max(0, (lastRefillMs + refillPeriodMs) - System.currentTimeMillis());
        }

        private void refillIfNeeded() {
            if (System.currentTimeMillis() - lastRefillMs >= refillPeriodMs) {
                tokens       = capacity;
                lastRefillMs = System.currentTimeMillis();
            }
        }
    }
}
