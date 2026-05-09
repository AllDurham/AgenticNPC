package com.agenticnpc.model;

/**
 * LLM 返回的结构化响应。
 * 对应 Prompt 中定义的 JSON Schema。
 *
 * 注意：字段名使用下划线命名，与 JSON 字段直接对应，
 * Gson 反序列化时无需额外配置。
 */
public record LLMResponse(
    String           dialogue,
    String           action_type,
    ActionParameters action_parameters
) {
    /** 判断是否携带有效动作 */
    public boolean hasAction() {
        return action_type != null
            && !action_type.isBlank()
            && !"NONE".equalsIgnoreCase(action_type);
    }
}
