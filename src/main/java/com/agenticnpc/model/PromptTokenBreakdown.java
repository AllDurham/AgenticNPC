package com.agenticnpc.model;

/**
 * Prompt Token 分段统计。
 * 使用现有 estimateTokens 算法（CJK/2 + ASCII/4），不引入 tokenizer 依赖。
 */
public record PromptTokenBreakdown(
    int systemTokens,      // 角色设定
    int profileTokens,     // 永久画像
    int summaryTokens,     // 压缩摘要
    int emotionTokens,     // 情绪值
    int playerInfoTokens,  // 玩家信息
    int actionTokens,      // 可执行动作
    int formatTokens,      // 输出格式约束
    int historyTokens,     // 对话历史
    int userInputTokens,   // 用户输入
    int totalTokens        // 累加总和
) {
    public static PromptTokenBreakdown empty() {
        return new PromptTokenBreakdown(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * 固定 token 成本 = format + actions。
     * 不含 system（含动态上下文）、profile、summary、emotion、playerInfo、history、input。
     */
    public int fixedCost() {
        return formatTokens + actionTokens;
    }
}
