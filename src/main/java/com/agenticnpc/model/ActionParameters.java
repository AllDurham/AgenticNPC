package com.agenticnpc.model;

/**
 * LLM 返回的动作参数。
 * 当 action_type 为 GIVE_ITEM 时携带此对象。
 */
public record ActionParameters(
    String item_id,
    int    amount
) {}
