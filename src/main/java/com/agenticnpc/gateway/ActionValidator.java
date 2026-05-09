package com.agenticnpc.gateway;

import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.gateway.model.ValidationResult;
import com.agenticnpc.model.ActionType;
import com.agenticnpc.model.LLMResponse;

import java.util.logging.Logger;

/**
 * 结构化动作校验器。
 *
 * 校验 LLM 返回的 action_type 是否在该 Brain 的允许范围内。
 * 使用枚举精确匹配，不做字符串前缀匹配，防止绕过。
 */
public class ActionValidator {

    private final ConfigManager config;
    private final Logger        logger;

    public ActionValidator(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /**
     * 校验 LLM 响应中的动作是否合法。
     *
     * @param response LLM 解析后的响应对象
     * @param brainId  当前 NPC 的 Brain ID
     * @return 校验结果
     */
    public ValidationResult validate(LLMResponse response, String brainId) {
        // NONE 类型：只说话，始终允许
        if (!response.hasAction()) {
            return ValidationResult.valid(ActionType.NONE, null);
        }

        // 解析动作类型（未知值 fromString 返回 NONE）
        ActionType actionType = ActionType.fromString(response.action_type());
        if (actionType == ActionType.NONE) {
            logger.warning(String.format(
                "[安全][ActionValidator] 未知动作类型被降级为 NONE | 原始值: %s | BrainId: %s",
                response.action_type(), brainId
            ));
            return ValidationResult.valid(ActionType.NONE, null);
        }

        // 检查 Brain 白名单
        BrainConfig brain = config.getBrainConfig(brainId).orElse(null);
        if (brain == null) {
            logger.warning("[安全][ActionValidator] 找不到 BrainConfig: " + brainId);
            return ValidationResult.invalid("Brain 配置不存在: " + brainId);
        }

        if (!brain.allowedActionTypes().contains(actionType.name())) {
            logger.warning(String.format(
                "[安全][ActionValidator] 越权动作被拦截 | BrainId: %s | 请求: %s | 允许: %s",
                brainId, actionType.name(), brain.allowedActionTypes()
            ));
            return ValidationResult.invalid("该 NPC 无权执行: " + actionType.name());
        }

        // GIVE_ITEM 必须携带参数
        if (actionType == ActionType.GIVE_ITEM && response.action_parameters() == null) {
            return ValidationResult.invalid("GIVE_ITEM 缺少 action_parameters");
        }

        return ValidationResult.valid(actionType, response.action_parameters());
    }
}
