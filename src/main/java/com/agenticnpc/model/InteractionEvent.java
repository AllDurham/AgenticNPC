package com.agenticnpc.model;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * 统一交互事件模型。
 * 由 Layer 1 各 Hook 生成，屏蔽底层插件差异。
 * Record 保证不可变性，线程安全传递。
 */
public record InteractionEvent(
    Player player,
    Entity npcEntity,
    String npcBrainId,
    String sanitizedInput,
    long   timestampMs
) {}
