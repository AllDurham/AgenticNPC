package com.agenticnpc.guard;

import com.agenticnpc.config.ConfigManager;
import org.bukkit.potion.PotionEffectType;

import java.util.Set;
import java.util.logging.Logger;

/**
 * 药水效果安全守卫。
 *
 * 防护目标：
 * 1. 未知效果名 -> 崩服风险
 * 2. 超出白名单的效果 -> 越权风险（如 WITHER / INSTANT_DAMAGE 恶意效果）
 * 3. 时长/等级越界 -> 恶意 buff 风险
 */
public class PotionGuard {

    private static final int MAX_DURATION_SECONDS = 300;
    private static final int MAX_AMPLIFIER = 5;

    private final ConfigManager config;
    private final Logger        logger;

    public PotionGuard(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public record PotionCheckResult(boolean safe, PotionEffectType type, int durationTicks, int amplifier, String reason) {
        public static PotionCheckResult safe(PotionEffectType type, int durationTicks, int amplifier) {
            return new PotionCheckResult(true, type, durationTicks, amplifier, null);
        }
        public static PotionCheckResult unsafe(String reason) {
            return new PotionCheckResult(false, null, 0, 0, reason);
        }
    }

    /**
     * 检查药水效果是否安全。
     *
     * @param effectName      效果名（如 "SPEED"、"HEAL"）
     * @param durationSeconds 持续秒数
     * @param amplifier       等级
     * @param brainId         Brain ID（用于日志）
     */
    public PotionCheckResult check(String effectName, int durationSeconds, int amplifier, String brainId) {
        // 1. 解析效果类型
        PotionEffectType type = PotionEffectType.getByName(effectName.toUpperCase());
        if (type == null) {
            logger.warning(String.format(
                "[安全][PotionGuard] 未知药水效果 | 效果: %s | BrainId: %s",
                effectName, brainId
            ));
            return PotionCheckResult.unsafe("未知效果类型: " + effectName);
        }

        // 2. 白名单校验
        Set<String> allowedEffects = config.getAllowedPotionEffects(brainId);
        if (!allowedEffects.isEmpty() && !allowedEffects.contains(effectName.toUpperCase())) {
            logger.warning(String.format(
                "[安全][PotionGuard] 越权药水效果被拦截 | 效果: %s | BrainId: %s | 白名单: %s",
                effectName, brainId, allowedEffects
            ));
            return PotionCheckResult.unsafe("该 NPC 无权施加效果: " + effectName);
        }

        // 3. 时长安全截断
        int safeDuration = Math.min(Math.max(durationSeconds, 1), MAX_DURATION_SECONDS) * 20;

        // 4. 等级安全截断
        int safeAmplifier = Math.min(Math.max(amplifier, 0), MAX_AMPLIFIER);

        if (config.isDebugMode()) {
            logger.info(String.format(
                "[Debug][PotionGuard] 药水校验通过 | 效果: %s | 时长: %ds | 等级: %d | BrainId: %s",
                effectName, durationSeconds, amplifier, brainId
            ));
        }

        return PotionCheckResult.safe(type, safeDuration, safeAmplifier);
    }
}
