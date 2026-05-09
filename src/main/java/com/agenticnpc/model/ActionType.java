package com.agenticnpc.model;

/**
 * 支持的动作类型枚举。
 * LLM 只能返回此枚举中定义的值，任何未知值均被网关拦截。
 */
public enum ActionType {
    NONE,
    GIVE_ITEM,
    TELEPORT,
    GIVE_EFFECT;

    /**
     * 安全解析：将字符串转换为枚举，未知值返回 NONE。
     */
    public static ActionType fromString(String value) {
        if (value == null || value.isBlank()) return NONE;
        try {
            return valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
