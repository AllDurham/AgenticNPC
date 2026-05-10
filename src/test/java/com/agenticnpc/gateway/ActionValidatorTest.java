package com.agenticnpc.gateway;

import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.gateway.model.ValidationResult;
import com.agenticnpc.model.ActionParameters;
import com.agenticnpc.model.ActionType;
import com.agenticnpc.model.LLMResponse;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionValidatorTest {

    private ActionValidator validator;
    private ConfigManager   config;
    private BrainConfig     brain;

    private static final String BRAIN_ID = "test_npc";

    /** 快速创建 null-filled ActionParameters，仅设置需要的字段 */
    private ActionParameters params(int nullSlotCount) {
        // 18 fields total, fill all with null except caller picks
        return new ActionParameters(
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, null
        );
    }

    @BeforeEach
    void setUp() {
        config    = mock(ConfigManager.class);
        Logger logger = Logger.getLogger("test");
        validator = new ActionValidator(config, logger);

        brain = new BrainConfig(
            BRAIN_ID, "测试NPC", "personality", "fallback",
            Set.of("GIVE_ITEM", "TELEPORT", "GIVE_EFFECT",
                   "SEND_TITLE", "PLAY_SOUND", "GIVE_XP"),
            Set.of(Material.BREAD, Material.APPLE, Material.DIAMOND),
            Set.of("HEAL", "REGENERATION"),
            "ENTITY_VILLAGER_AMBIENT", 1.0f, 1.0f, true,
            "brain", false, "NEUTRAL",
            Set.of("ENTITY_VILLAGER_AMBIENT", "ENTITY_VILLAGER_HAPPY")
        );
        when(config.getBrainConfig(BRAIN_ID)).thenReturn(Optional.of(brain));
    }

    // ---- NONE 类型始终允许 ----

    @Test
    @DisplayName("action_type=NONE → 始终 valid")
    void noneTypeAlwaysValid() {
        LLMResponse resp = new LLMResponse("你好", "NONE", null, null);
        ValidationResult result = validator.validate(resp, BRAIN_ID);
        assertTrue(result.valid());
        assertEquals(ActionType.NONE, result.actionType());
    }

    @Test
    @DisplayName("hasAction()=false → valid NONE")
    void hasActionFalseYieldsValid() {
        LLMResponse resp = new LLMResponse("你好", null, null, null);
        ValidationResult result = validator.validate(resp, BRAIN_ID);
        assertTrue(result.valid());
        assertEquals(ActionType.NONE, result.actionType());
    }

    // ---- 未知动作降级为 NONE ----

    @Test
    @DisplayName("未知 action_type 降级为 NONE")
    void unknownActionTypeDowngradesToNone() {
        LLMResponse resp = new LLMResponse("你好", "HACK", null, null);
        ValidationResult result = validator.validate(resp, BRAIN_ID);
        assertTrue(result.valid());
        assertEquals(ActionType.NONE, result.actionType());
    }

    // ---- 白名单拦截 ----

    @Test
    @DisplayName("不在 Brain 白名单的动作被拦截")
    void actionNotInWhitelistBlocked() {
        BrainConfig limited = new BrainConfig(
            "limited", "限制NPC", "p", "f",
            Set.of("GIVE_EFFECT"), Set.of(), Set.of("HEAL"),
            "", 0, 0, false, "brain", false, "NEUTRAL", Set.of()
        );
        when(config.getBrainConfig("limited")).thenReturn(Optional.of(limited));

        LLMResponse resp = new LLMResponse("给你", "GIVE_ITEM",
            new ActionParameters("BREAD", 1, null, null, null, null,
                                 null, null, null, null, null, null,
                                 null, null, null, null, null, null), null);
        ValidationResult result = validator.validate(resp, "limited");
        assertFalse(result.valid());
        assertTrue(result.failReason().contains("无权执行"));
    }

    // ---- 缺少参数 ----

    @Test
    @DisplayName("GIVE_ITEM 缺少 action_parameters 被拒绝")
    void giveItemMissingParams() {
        LLMResponse resp = new LLMResponse("给你", "GIVE_ITEM", null, null);
        ValidationResult result = validator.validate(resp, BRAIN_ID);
        assertFalse(result.valid());
        assertTrue(result.failReason().contains("缺少 action_parameters"));
    }

    @Test
    @DisplayName("SEND_TITLE 缺少 title_text 被拒绝")
    void sendTitleMissingTitleText() {
        LLMResponse resp = new LLMResponse("看这里", "SEND_TITLE", params(0), null);
        ValidationResult result = validator.validate(resp, BRAIN_ID);
        assertFalse(result.valid());
        assertTrue(result.failReason().contains("title_text"));
    }

    // ---- PLAY_SOUND 白名单校验 ----

    @Test
    @DisplayName("PLAY_SOUND 音效不在白名单被拒绝")
    void playSoundNotInWhitelist() {
        ActionParameters p = new ActionParameters(
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, "ENTITY_ZOMBIE_AMBIENT", null, null, null);
        LLMResponse resp = new LLMResponse("听", "PLAY_SOUND", p, null);
        ValidationResult result = validator.validate(resp, BRAIN_ID);
        assertFalse(result.valid());
        assertTrue(result.failReason().contains("不在白名单"));
    }

    @Test
    @DisplayName("PLAY_SOUND 音效在白名单中通过")
    void playSoundInWhitelist() {
        ActionParameters p = new ActionParameters(
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, "ENTITY_VILLAGER_AMBIENT", 1.0, 1.0, null);
        LLMResponse resp = new LLMResponse("听", "PLAY_SOUND", p, null);
        ValidationResult result = validator.validate(resp, BRAIN_ID);
        assertTrue(result.valid());
        assertEquals(ActionType.PLAY_SOUND, result.actionType());
    }

    // ---- GIVE_XP 参数校验 ----

    @Test
    @DisplayName("GIVE_XP 数量 ≤ 0 被拒绝")
    void giveXpZeroOrNegative() {
        ActionParameters zero = new ActionParameters(
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, 0);
        assertFalse(validator.validate(
            new LLMResponse("经验", "GIVE_XP", zero, null), BRAIN_ID).valid());

        ActionParameters neg = new ActionParameters(
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, -5);
        assertFalse(validator.validate(
            new LLMResponse("经验", "GIVE_XP", neg, null), BRAIN_ID).valid());
    }

    @Test
    @DisplayName("GIVE_XP 数量 > 0 通过校验")
    void giveXpPositive() {
        ActionParameters p = new ActionParameters(
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, null, null, null, 100);
        ValidationResult result = validator.validate(
            new LLMResponse("经验", "GIVE_XP", p, null), BRAIN_ID);
        assertTrue(result.valid());
        assertEquals(ActionType.GIVE_XP, result.actionType());
    }

    // ---- Brain 不存在 ----

    @Test
    @DisplayName("BrainId 不存在时返回 invalid")
    void brainNotFound() {
        when(config.getBrainConfig("nonexistent")).thenReturn(Optional.empty());
        LLMResponse resp = new LLMResponse("你好", "GIVE_ITEM",
            new ActionParameters("BREAD", 1, null, null, null, null,
                                 null, null, null, null, null, null,
                                 null, null, null, null, null, null), null);
        ValidationResult result = validator.validate(resp, "nonexistent");
        assertFalse(result.valid());
        assertTrue(result.failReason().contains("不存在"));
    }

    // ---- 正常通过校验 ----

    @Test
    @DisplayName("GIVE_ITEM 在白名单且参数完整 → valid")
    void giveItemValid() {
        LLMResponse resp = new LLMResponse("给你面包", "GIVE_ITEM",
            new ActionParameters("BREAD", 3, null, null, null, null,
                                 null, null, null, null, null, null,
                                 null, null, null, null, null, null), null);
        ValidationResult result = validator.validate(resp, BRAIN_ID);
        assertTrue(result.valid());
        assertEquals(ActionType.GIVE_ITEM, result.actionType());
        assertEquals("BREAD", result.parameters().item_id());
        assertEquals(3, result.parameters().amount());
    }

    @Test
    @DisplayName("SEND_TITLE 参数完整 → valid")
    void sendTitleValid() {
        LLMResponse resp = new LLMResponse("看标题", "SEND_TITLE",
            new ActionParameters(null, null, null, null, null, null,
                                 null, null, null,
                                 "§e欢迎", "§7旅途愉快", 10, 60, 20,
                                 null, null, null, null), null);
        ValidationResult result = validator.validate(resp, BRAIN_ID);
        assertTrue(result.valid());
        assertEquals(ActionType.SEND_TITLE, result.actionType());
    }

    // ---- 动态白名单测试（sound-whitelist 为空时放行）----

    @Test
    @DisplayName("sound-whitelist 为空时 PLAY_SOUND 放行")
    void playSoundNoWhitelistRestriction() {
        BrainConfig noWhitelistBrain = new BrainConfig(
            "open", "开放NPC", "p", "f",
            Set.of("PLAY_SOUND"), Set.of(), Set.of(),
            "", 0, 0, false, "brain", false, "NEUTRAL", Set.of()
        );
        when(config.getBrainConfig("open")).thenReturn(Optional.of(noWhitelistBrain));

        ActionParameters p = new ActionParameters(
            null, null, null, null, null, null,
            null, null, null, null, null, null,
            null, null, "ANY_SOUND", 1.0, 1.0, null);
        LLMResponse resp = new LLMResponse("听", "PLAY_SOUND", p, null);
        ValidationResult result = validator.validate(resp, "open");
        assertTrue(result.valid());
    }
}
