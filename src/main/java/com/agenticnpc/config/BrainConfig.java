package com.agenticnpc.config;

import org.bukkit.Material;
import java.util.Set;

/**
 * 单个 NPC Brain 的配置快照（不可变）。
 */
public record BrainConfig(
    String        id,
    String        name,
    String        personality,
    String        fallbackDialogue,
    Set<String>   allowedActionTypes,
    Set<Material> allowedItems,
    Set<String>   allowedPotionEffects,
    String        dialogueSound,
    float         dialogueSoundVolume,
    float         dialogueSoundPitch,
    boolean       actionBarEnabled
) {}
