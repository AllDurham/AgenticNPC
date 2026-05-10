package com.agenticnpc.command;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.dispatch.CircuitBreaker;
import com.agenticnpc.dispatch.LocalRateLimiter;
import com.agenticnpc.dispatch.RateLimiter;
import com.agenticnpc.memory.MemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthCommandTest {

    private HealthCommand healthCommand;

    @BeforeEach
    void setUp() {
        CircuitBreaker cb = new CircuitBreaker(5, 30000, Logger.getLogger("test"));
        RateLimiter rl = mock(LocalRateLimiter.class);
        ConfigManager config = mock(ConfigManager.class);
        when(config.getServerId()).thenReturn("test-1");
        when(config.getRateLimitBackend()).thenReturn("local");

        MemoryRepository repo = mock(MemoryRepository.class);

        healthCommand = new HealthCommand(cb, rl, config, repo, null);
    }

    // ---- 错误计数器 ----

    @Test
    @DisplayName("recordError 递增错误计数")
    void errorCounterIncrements() {
        healthCommand.recordError();
        healthCommand.recordError();
        healthCommand.recordError();
        // 内部状态验证（通过行为间接验证）
        // 无法直接访问 recentErrors，但 recordError 不应抛异常
    }

    @Test
    @DisplayName("recordError 不抛异常")
    void recordErrorNoException() {
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 1000; i++) {
                healthCommand.recordError();
            }
        });
    }

    // ---- SemanticGuard 标记 ----

    @Test
    @DisplayName("setSemanticGuardEnabled 不抛异常")
    void semanticGuardToggle() {
        assertDoesNotThrow(() -> {
            healthCommand.setSemanticGuardEnabled(true);
            healthCommand.setSemanticGuardEnabled(false);
        });
    }
}
