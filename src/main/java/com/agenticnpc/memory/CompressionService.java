package com.agenticnpc.memory;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.dispatch.LLMClient;
import com.agenticnpc.model.ChatMessage;

import java.util.*;
import java.util.logging.Logger;

/**
 * 对话摘要压缩服务。
 *
 * 触发条件：
 * 1. 玩家退出时，轮数 >= compression.exit-threshold（默认5轮）
 * 2. 对话进行中，轮数 >= compression.active-threshold（默认10轮）
 */
public class CompressionService {

    private static final String COMPRESSION_SYSTEM_PROMPT = """
        你是一个专业的对话摘要助手。
        你的任务是将下面的对话历史压缩成简洁的第三人称摘要。
        摘要要求：
        1. 保留所有重要事件（给予了什么物品、玩家透露了什么信息、发生了什么关键剧情）
        2. 保留玩家的基本特征（名字、处境、性格倾向）
        3. 去除寒暄、重复内容
        4. 使用第三人称，例如「玩家暖树向艾尔文自我介绍，获赠了两个苹果...」
        5. 摘要长度严格控制在 300 字以内
        只输出摘要文本，不要输出任何其他内容。
        """;

    private final LLMClient     compressionClient;
    private final ConfigManager config;
    private final Logger        logger;

    public CompressionService(LLMClient compressionClient, ConfigManager config, Logger logger) {
        this.compressionClient = compressionClient;
        this.config            = config;
        this.logger            = logger;
    }

    public void compressAsync(UUID playerId, String brainId,
                               Deque<ChatMessage> history,
                               MemoryRepository repository) {
        if (history.size() < 2) return;

        logger.info(String.format("[压缩] 开始压缩 | 玩家: %s | Brain: %s | 历史条数: %d",
            playerId, brainId, history.size()));

        String historyText = formatHistoryForCompression(history);

        compressionClient.sendRawAsync(COMPRESSION_SYSTEM_PROMPT, historyText)
            .whenComplete((result, throwable) -> {
                if (throwable != null || result == null) {
                    logger.warning("[压缩] 压缩失败，保留原始历史 | 错误: "
                        + (throwable != null ? throwable.getMessage() : "null result"));
                    return;
                }

                String summary = result.contentText().trim();
                int tokenCount = estimateTokenCount(summary);

                int maxTokens = config.getCompressionMaxSummaryTokens();
                if (tokenCount > maxTokens) {
                    summary = truncateToTokenLimit(summary, maxTokens);
                    logger.warning("[压缩] 摘要超出 Token 上限，已截断");
                }

                repository.saveSummary(playerId, brainId, summary, tokenCount, history.size() / 2);
                history.clear();
                repository.save(playerId, brainId, history);

                logger.info(String.format("[压缩] 压缩完成 | 玩家: %s | Brain: %s | 摘要长度: %d chars",
                    playerId, brainId, summary.length()));
            });
    }

    public Optional<String> loadSummary(UUID playerId, String brainId,
                                         MemoryRepository repository) {
        return repository.loadSummary(playerId, brainId);
    }

    private String formatHistoryForCompression(Deque<ChatMessage> history) {
        StringBuilder sb = new StringBuilder("以下是需要压缩的对话历史：\n\n");
        for (ChatMessage msg : history) {
            String role = "user".equals(msg.role()) ? "玩家" : "NPC";
            sb.append(role).append(": ").append(msg.content()).append("\n");
        }
        return sb.toString();
    }

    private int estimateTokenCount(String text) {
        int chineseCount = 0, otherCount = 0;
        for (char c : text.toCharArray()) {
            if (c >= '一' && c <= '鿿') chineseCount++;
            else otherCount++;
        }
        return (int)(chineseCount / 1.5 + otherCount / 4.0);
    }

    private String truncateToTokenLimit(String text, int maxTokens) {
        int estimatedMaxChars = (int)(maxTokens * 2.0);
        return text.length() > estimatedMaxChars
            ? text.substring(0, estimatedMaxChars) + "..." : text;
    }
}
