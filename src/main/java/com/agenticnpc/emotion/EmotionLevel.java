package com.agenticnpc.emotion;

/**
 * NPC 情绪档位（离散枚举）。
 */
public enum EmotionLevel {
    HOSTILE("敌对", "你对该玩家极度反感，拒绝提供任何帮助，语气冷漠甚至充满敌意"),
    WARY("警惕", "你对该玩家保持距离，不愿透露信息，拒绝给予物品，语气冷淡"),
    NEUTRAL("中立", "你对该玩家态度平常，正常对话，视情况决定是否提供帮助"),
    FRIENDLY("友善", "你对该玩家印象不错，乐于助人，语气温和亲切"),
    DEVOTED("忠诚", "你非常信任该玩家，主动提供帮助，愿意分享一切");

    public final String displayName;
    public final String promptDescription;

    EmotionLevel(String displayName, String promptDescription) {
        this.displayName       = displayName;
        this.promptDescription = promptDescription;
    }

    public EmotionLevel upgrade() {
        EmotionLevel[] values = values();
        return values[Math.min(this.ordinal() + 1, values.length - 1)];
    }

    public EmotionLevel downgrade() {
        EmotionLevel[] values = values();
        return values[Math.max(this.ordinal() - 1, 0)];
    }

    public static EmotionLevel fromString(String value) {
        try { return valueOf(value.toUpperCase()); }
        catch (Exception e) { return NEUTRAL; }
    }
}
