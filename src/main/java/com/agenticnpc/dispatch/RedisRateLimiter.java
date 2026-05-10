package com.agenticnpc.dispatch;

import com.agenticnpc.config.ConfigManager;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.params.SetParams;

import java.util.Collections;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Redis 跨服滑动窗口限流器。
 *
 * 使用 Redis INCR + EXPIRE 实现固定窗口计数器。
 * Key 维度：playerUUID::brainId（或 playerUUID 当 global-per-player=true）。
 *
 * Fail-open 策略：Redis 不可用时允许请求通过，输出 WARN。
 * 所有 Redis IO 已在异步线程执行（由 AsyncDispatcher 保证）。
 */
public class RedisRateLimiter implements RateLimiter {

    private final JedisPool      pool;
    private final ConfigManager  config;
    private final Logger         logger;

    // Lua 脚本：原子性 INCR + EXPIRE
    // KEYS[1] = rate limit key
    // ARGV[1] = max requests
    // ARGV[2] = window seconds
    // 返回: 1 = allowed, 0 = rejected
    private static final String LUA_SCRIPT = """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
            redis.call('EXPIRE', KEYS[1], ARGV[2])
        end
        if current > tonumber(ARGV[1]) then
            return 0
        end
        return 1
        """;

    private String luaSha;  // 缓存的 SCRIPT LOAD SHA

    public RedisRateLimiter(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        String host = config.getRedisHost();
        int    port = config.getRedisPort();
        String password = config.getRedisPassword();
        int    database = config.getRedisDatabase();
        int    timeoutMs = config.getRedisTimeoutMs();

        if (password != null && !password.isEmpty()) {
            pool = new JedisPool(poolConfig, host, port, timeoutMs, password, database);
        } else {
            pool = new JedisPool(poolConfig, host, port, timeoutMs, null, database);
        }

        // 预加载 Lua 脚本
        try (Jedis jedis = pool.getResource()) {
            luaSha = jedis.scriptLoad(LUA_SCRIPT);
            logger.info("[限流] Redis 连接成功 | " + host + ":" + port + " | db=" + database);
        } catch (Exception e) {
            logger.warning("[限流] Redis 连接失败（限流将降级为 fail-open）: " + e.getMessage());
        }
    }

    @Override
    public RateLimitResult tryAcquire(UUID playerId, String brainId) {
        String key = buildKey(playerId, brainId);
        int    maxRequests = config.getRateLimitMax();
        long   periodMs    = config.getRateLimitPeriodMs();
        int    windowSec   = (int) Math.max(1, periodMs / 1000);

        try (Jedis jedis = pool.getResource()) {
            Object result;
            if (luaSha != null) {
                try {
                    result = jedis.evalsha(luaSha,
                        Collections.singletonList(key),
                        java.util.List.of(
                            String.valueOf(maxRequests),
                            String.valueOf(windowSec)
                        ));
                } catch (redis.clients.jedis.exceptions.JedisNoScriptException e) {
                    // 脚本缓存丢失，重新加载
                    luaSha = jedis.scriptLoad(LUA_SCRIPT);
                    result = jedis.evalsha(luaSha,
                        Collections.singletonList(key),
                        java.util.List.of(
                            String.valueOf(maxRequests),
                            String.valueOf(windowSec)
                        ));
                }
            } else {
                // fallback: 直接 eval
                result = jedis.eval(LUA_SCRIPT,
                    Collections.singletonList(key),
                    java.util.List.of(
                        String.valueOf(maxRequests),
                        String.valueOf(windowSec)
                    ));
            }

            Long allowed = (Long) result;
            if (allowed != null && allowed == 1L) {
                return RateLimitResult.allow();
            }

            // 被拒绝，计算剩余窗口时间
            long ttl = jedis.ttl(key);
            long waitSec = ttl > 0 ? ttl : windowSec;
            return RateLimitResult.reject(
                "对话过于频繁，请等待 " + waitSec + " 秒后再试。（跨服限流）"
            );

        } catch (Exception e) {
            // Fail-open: Redis 不可用时允许请求通过
            logger.warning("[限流] Redis 操作失败（fail-open）: " + e.getMessage());
            return RateLimitResult.allow();
        }
    }

    @Override
    public void shutdown() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
            logger.info("[限流] Redis 连接池已关闭");
        }
    }

    /**
     * Redis ping 检测（供 Health 命令使用）。
     * @return true 如果 Redis 可用
     */
    public boolean ping() {
        try (Jedis jedis = pool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    private String buildKey(UUID playerId, String brainId) {
        String suffix = config.isGlobalRateLimit() ? "global" : brainId;
        return "anpc:ratelimit:" + playerId + ":" + suffix;
    }
}
