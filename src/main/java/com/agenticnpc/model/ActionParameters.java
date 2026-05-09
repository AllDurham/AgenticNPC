package com.agenticnpc.model;

/**
 * LLM 返回的动作参数。
 * 所有字段允许为 null，由各 ActionType 按需读取。
 */
public record ActionParameters(
    // GIVE_ITEM
    String item_id,
    Integer amount,

    // TELEPORT
    String world,
    Double x,
    Double y,
    Double z,

    // GIVE_EFFECT
    String effect_name,
    Integer duration_seconds,
    Integer amplifier
) {}
