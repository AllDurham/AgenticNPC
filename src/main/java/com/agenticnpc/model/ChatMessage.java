package com.agenticnpc.model;

/**
 * 单条对话消息。
 * role: "system" / "user" / "assistant"
 */
public record ChatMessage(
    String role,
    String content,
    long   timestampMs
) {
    /** 便捷构造，自动填充当前时间戳 */
    public ChatMessage(String role, String content) {
        this(role, content, System.currentTimeMillis());
    }
}
