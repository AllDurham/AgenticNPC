package com.agenticnpc.gateway.model;

import org.bukkit.Material;

public record ItemSafetyResult(
    boolean  safe,
    Material material,
    int      amount,
    String   reason
) {
    public static ItemSafetyResult safe(Material material, int amount) {
        return new ItemSafetyResult(true, material, amount, null);
    }

    public static ItemSafetyResult unsafe(String reason) {
        return new ItemSafetyResult(false, null, 0, reason);
    }
}
