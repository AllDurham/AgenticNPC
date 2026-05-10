package com.agenticnpc.dispatch;

import java.util.UUID;

/**
 * 限流器接口。
 *
 * 支持两种实现：
 * - LocalRateLimiter：本地 JVM 令牌桶（默认）
 * - RedisRateLimiter：Redis 跨服滑动窗口
 */
public interface RateLimiter {

    record RateLimitResult(boolean allowed, String rejectMessage) {
        public static RateLimitResult allow()               { return new RateLimitResult(true,  null); }
        public static RateLimitResult reject(String reason) { return new RateLimitResult(false, reason); }
    }

    /**
     * 尝试为该玩家 + NPC 组合消耗一个令牌。
     */
    RateLimitResult tryAcquire(UUID playerId, String brainId);

    /** 关闭限流器，释放资源。 */
    default void shutdown() {}
}
