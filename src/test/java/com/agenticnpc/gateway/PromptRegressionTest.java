package com.agenticnpc.gateway;

import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.emotion.EmotionManager;
import com.agenticnpc.memory.CompressionService;
import com.agenticnpc.memory.MemoryManager;
import com.agenticnpc.memory.PlayerProfileManager;
import com.agenticnpc.model.ActionType;
import com.agenticnpc.model.LLMResponse;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prompt 回归测试。
 *
 * 从 fixtures/prompt_regression.json 读取测试用例，
 * 每条用例包含：description, llm_output (JSON), expected_action, expected_dialogue_contains。
 *
 * 目的：确保 PromptBuilder 的输出格式约束得到 LLM 遵守，
 * 且 LLMResponseParser 能正确解析各类响应。
 */
class PromptRegressionTest {

    private LLMResponseParser parser;
    private ActionValidator    validator;
    private ConfigManager      config;
    private final Gson         gson = new Gson();

    private static final String BRAIN_ID = "default_npc";

    @BeforeEach
    void setUp() {
        parser = new LLMResponseParser(Logger.getLogger("regression"));
        config = org.mockito.Mockito.mock(ConfigManager.class);

        BrainConfig brain = new BrainConfig(
            BRAIN_ID, "艾尔文", "personality", "fallback",
            Set.of("GIVE_ITEM", "TELEPORT", "GIVE_EFFECT",
                   "SEND_TITLE", "PLAY_SOUND", "GIVE_XP"),
            Set.of(Material.BREAD, Material.APPLE, Material.DIAMOND),
            Set.of("HEAL", "REGENERATION"),
            "ENTITY_VILLAGER_AMBIENT", 1.0f, 1.0f, true,
            "brain", false, "NEUTRAL",
            Set.of("ENTITY_VILLAGER_AMBIENT", "ENTITY_VILLAGER_HAPPY")
        );
        org.mockito.Mockito.when(config.getBrainConfig(BRAIN_ID))
            .thenReturn(Optional.of(brain));
        org.mockito.Mockito.when(config.isDebugMode()).thenReturn(false);

        validator = new ActionValidator(config, Logger.getLogger("regression"));
    }

    record RegressionCase(
        String description,
        String llm_output,
        String expected_action,
        String expected_dialogue_contains
    ) {}

    static Stream<RegressionCase> regressionCases() throws IOException {
        Gson gson = new Gson();
        InputStream is = PromptRegressionTest.class.getClassLoader()
            .getResourceAsStream("fixtures/prompt_regression.json");
        assertNotNull(is, "fixtures/prompt_regression.json not found on classpath");
        List<RegressionCase> cases = gson.fromJson(
            new InputStreamReader(is, StandardCharsets.UTF_8),
            new TypeToken<List<RegressionCase>>(){}.getType()
        );
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("regressionCases")
    @DisplayName("Prompt 回归：LLM 输出 → 解析 → 校验")
    void regression(RegressionCase testCase) {
        // 1. 解析 LLM 输出
        var parsed = parser.parse(testCase.llm_output());
        assertTrue(parsed.isPresent(),
            "解析失败: " + testCase.description() + " | 原始: " + testCase.llm_output());

        LLMResponse response = parsed.get();

        // 2. 对话内容包含断言
        if (testCase.expected_dialogue_contains() != null) {
            assertTrue(response.dialogue().contains(testCase.expected_dialogue_contains()),
                "对话内容不符: 期望包含 '" + testCase.expected_dialogue_contains()
                + "', 实际: '" + response.dialogue() + "'");
        }

        // 3. 动作校验
        var validation = validator.validate(response, BRAIN_ID);
        if ("NONE".equals(testCase.expected_action())) {
            // NONE 可能是 validator 降级的结果，也可能是直接 valid NONE
            assertEquals(ActionType.NONE, validation.actionType());
        } else {
            assertTrue(validation.valid(),
                "校验应通过: " + testCase.description() + " | 原因: " + validation.failReason());
            assertEquals(
                ActionType.valueOf(testCase.expected_action()),
                validation.actionType(),
                "动作类型不符: " + testCase.description()
            );
        }
    }
}
