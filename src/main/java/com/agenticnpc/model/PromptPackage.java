package com.agenticnpc.model;

import com.agenticnpc.config.BrainConfig;
import java.util.List;

/**
 * 组装完毕的 Prompt 包，交付给 LLM 通信层。
 */
public record PromptPackage(
    String          systemPrompt,
    List<ChatMessage> history,
    ChatMessage     userMessage,
    BrainConfig     brain
) {}
