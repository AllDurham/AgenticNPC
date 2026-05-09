package com.agenticnpc.model;

import com.agenticnpc.emotion.EmotionChange;

/**
 * LLM 返回的结构化响应。
 */
public record LLMResponse(
    String           dialogue,
    String           action_type,
    ActionParameters action_parameters,
    String           emotion_change
) {
    public boolean hasAction() {
        return action_type != null
            && !action_type.isBlank()
            && !"NONE".equalsIgnoreCase(action_type);
    }

    public EmotionChange getEmotionChange() {
        return EmotionChange.fromString(emotion_change != null ? emotion_change : "NONE");
    }
}
