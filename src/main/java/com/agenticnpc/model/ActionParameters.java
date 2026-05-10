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
    Integer amplifier,

    // SEND_TITLE
    String title_text,
    String title_subtitle,
    Integer title_fade_in,
    Integer title_stay,
    Integer title_fade_out,

    // PLAY_SOUND
    String sound_name,
    Double sound_volume,
    Double sound_pitch,

    // GIVE_XP
    Integer xp_amount
) {}
