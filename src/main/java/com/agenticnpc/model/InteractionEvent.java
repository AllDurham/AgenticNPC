package com.agenticnpc.model;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 统一交互事件模型。
 * 由 Layer 1 各 Hook 生成，屏蔽底层插件差异。
 * Record 保证不可变性，线程安全传递。
 * 每个事件携带唯一 traceId，用于全链路可观测。
 */
public record InteractionEvent(
    String traceId,
    Player player,
    Entity npcEntity,
    String npcBrainId,
    String sanitizedInput,
    long   timestampMs,
    boolean stress          // true = 压力测试，跳过 audit/snapshot/memory
) {
    /**
     * 统一工厂方法：从 Bukkit 运行时对象创建事件。
     * 自动生成 12 位短 UUID 作为 traceId。
     */
    public static InteractionEvent create(Player player, Entity npcEntity,
                                          String npcBrainId, String sanitizedInput) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return new InteractionEvent(
            traceId, player, npcEntity, npcBrainId, sanitizedInput,
            System.currentTimeMillis(), false
        );
    }

    /**
     * 压力测试工厂方法：标记 stress=true，跳过审计/快照/记忆写入。
     */
    public static InteractionEvent createStress(Player player, Entity npcEntity,
                                                String npcBrainId, String sanitizedInput) {
        String traceId = "stress-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return new InteractionEvent(
            traceId, player, npcEntity, npcBrainId, sanitizedInput,
            System.currentTimeMillis(), true
        );
    }
}
