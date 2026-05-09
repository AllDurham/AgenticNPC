package com.agenticnpc.emotion;

public enum EmotionChange {
    NONE,
    UPGRADE,
    DOWNGRADE;

    public static EmotionChange fromString(String value) {
        try { return valueOf(value.toUpperCase()); }
        catch (Exception e) { return NONE; }
    }
}
