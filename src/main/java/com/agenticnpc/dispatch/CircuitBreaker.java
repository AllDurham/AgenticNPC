package com.agenticnpc.dispatch;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * 熔断器（Circuit Breaker）。
 *
 * 状态机：
 *   CLOSED   — 正常状态，请求正常通过
 *   OPEN     — 熔断状态，请求快速失败，返回 fallback
 *   HALF_OPEN — 尝试恢复，放行一次请求，成功则回 CLOSED，失败则回 OPEN
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private volatile State         state        = State.CLOSED;
    private final    AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long          openedAtMs   = 0;

    private final int    failureThreshold;
    private final long   recoveryTimeMs;
    private final Logger logger;

    public CircuitBreaker(int failureThreshold, long recoveryTimeMs, Logger logger) {
        this.failureThreshold = failureThreshold;
        this.recoveryTimeMs   = recoveryTimeMs;
        this.logger           = logger;
    }

    /**
     * 通过熔断器执行异步操作。
     *
     * @param action   被保护的异步操作
     * @param fallback 熔断或异常时的降级值
     */
    public <T> CompletableFuture<T> execute(
            Supplier<CompletableFuture<T>> action,
            Supplier<T> fallback) {

        return switch (state) {
            case OPEN -> {
                // 检查是否可以进入半开状态
                if (System.currentTimeMillis() - openedAtMs > recoveryTimeMs) {
                    logger.info("[熔断器] 进入 HALF_OPEN，尝试恢复...");
                    state = State.HALF_OPEN;
                    yield executeWithTracking(action, fallback);
                }
                logger.warning("[熔断器] 熔断中，请求被快速失败");
                yield CompletableFuture.completedFuture(fallback.get());
            }
            case CLOSED, HALF_OPEN -> executeWithTracking(action, fallback);
        };
    }

    public State getState() { return state; }

    public void reset() {
        this.state = State.CLOSED;
        this.failureCount.set(0);
        this.openedAtMs = 0;
        logger.info("[熔断器] 状态已手动重置");
    }

    // ================================================================

    private <T> CompletableFuture<T> executeWithTracking(
            Supplier<CompletableFuture<T>> action,
            Supplier<T> fallback) {

        return action.get()
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    onFailure();
                } else {
                    onSuccess();
                }
            })
            .exceptionally(e -> fallback.get());
    }

    private void onSuccess() {
        if (state == State.HALF_OPEN) {
            logger.info("[熔断器] 恢复成功，切换回 CLOSED");
        }
        failureCount.set(0);
        state = State.CLOSED;
    }

    private void onFailure() {
        int failures = failureCount.incrementAndGet();
        logger.warning("[熔断器] 请求失败，累计: " + failures + "/" + failureThreshold);

        if (failures >= failureThreshold) {
            state      = State.OPEN;
            openedAtMs = System.currentTimeMillis();
            logger.severe("[熔断器] 已打开！" + recoveryTimeMs / 1000 + "s 后自动恢复");
        }
    }
}
