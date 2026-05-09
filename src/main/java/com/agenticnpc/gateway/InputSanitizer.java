package com.agenticnpc.gateway;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.gateway.model.SanitizeResult;

import java.text.Normalizer;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 输入清洗器（第一道防线）。
 * 在 Layer 1 接受玩家输入后立即执行。
 *
 * v1.0 基础版：长度截断 + 控制字符过滤 + 已知注入模式匹配。
 * v1.1 升级：语义级防御。
 */
public class InputSanitizer {

    private static final int MAX_INPUT_LENGTH = 100;

    /**
     * 已知 Prompt 注入模式（v1.0）。
     * 匹配到任意一条 -> 拒绝输入。
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("(?i)ignore\\s*(all\\s*)?previous\\s*instruction"),
        Pattern.compile("(?i)system\\s*prompt"),
        Pattern.compile("(?i)(你现在是|forget.*role|new.*instruction|act\\s+as)"),
        Pattern.compile("(?i)(disregard|override)\\s*(your|the)"),
        Pattern.compile("(?i)jailbreak"),
        Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]") // 控制字符
    );

    private final ConfigManager config;
    private final Logger        logger;

    public InputSanitizer(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /**
     * 清洗玩家原始输入。
     *
     * @param rawInput   玩家输入的原始字符串
     * @param playerName 玩家名（用于审计日志）
     * @return 清洗结果
     */
    public SanitizeResult sanitize(String rawInput, String playerName) {
        // 1. 空值检查
        if (rawInput == null || rawInput.isBlank()) {
            return SanitizeResult.reject("输入为空");
        }

        // 2. 长度截断（截断而非拒绝，减少玩家挫败感）
        String processed = rawInput.length() > MAX_INPUT_LENGTH
            ? rawInput.substring(0, MAX_INPUT_LENGTH)
            : rawInput;

        // 3. 注入模式检测
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(processed).find()) {
                logger.warning(String.format(
                    "[安全][InputSanitizer] Prompt 注入尝试被拦截 | 玩家: %s | 原始输入: %s",
                    playerName, rawInput
                ));
                return SanitizeResult.reject("检测到异常输入");
            }
        }

        // 4. 剥离标签逃逸（防 Prompt 注入突破 XML 隔离层）
        processed = processed.replaceAll("</(?i)user_input>", "< /user_input>");

        // 5. Unicode 规范化（防同形字攻击）
        String normalized = Normalizer.normalize(processed, Normalizer.Form.NFKC);

        if (config.isDebugMode()) {
            logger.info(String.format(
                "[Debug][InputSanitizer] 输入清洗完成 | 玩家: %s | 处理后: %s",
                playerName, normalized
            ));
        }

        return SanitizeResult.accept(normalized);
    }
}
