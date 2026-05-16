package com.agenticnpc.gateway;

import com.agenticnpc.model.LLMResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class LLMResponseParserTest {

    private LLMResponseParser parser;

    @BeforeEach
    void setUp() {
        parser = new LLMResponseParser(Logger.getLogger("test"));
    }

    // ---- 标准 JSON ----

    @Test
    @DisplayName("标准 JSON 正常解析")
    void standardJsonParsed() {
        String json = """
            {
              "dialogue": "你好，旅行者",
              "action_type": "NONE",
              "action_parameters": null,
              "emotion_change": "NONE"
            }""";
        var result = parser.parse(json);
        assertTrue(result.isPresent());
        assertEquals("你好，旅行者", result.get().dialogue());
        assertEquals("NONE", result.get().action_type());
        assertNull(result.get().action_parameters());
    }

    @Test
    @DisplayName("GIVE_ITEM 完整 JSON 解析")
    void giveItemParsed() {
        String json = """
            {"dialogue":"给你面包","action_type":"GIVE_ITEM","action_parameters":{"item_id":"BREAD","amount":3},"emotion_change":"NONE"}""";
        var result = parser.parse(json);
        assertTrue(result.isPresent());
        assertEquals("GIVE_ITEM", result.get().action_type());
        assertEquals("BREAD", result.get().action_parameters().item_id());
        assertEquals(3, result.get().action_parameters().amount());
    }

    @Test
    @DisplayName("SEND_TITLE JSON 解析")
    void sendTitleParsed() {
        String json = """
            {"dialogue":"看这里","action_type":"SEND_TITLE","action_parameters":{"title_text":"§e欢迎","title_subtitle":"§7旅途愉快","title_fade_in":10,"title_stay":60,"title_fade_out":20},"emotion_change":"NONE"}""";
        var result = parser.parse(json);
        assertTrue(result.isPresent());
        assertEquals("SEND_TITLE", result.get().action_type());
        assertEquals("§e欢迎", result.get().action_parameters().title_text());
        assertEquals("§7旅途愉快", result.get().action_parameters().title_subtitle());
        assertEquals(10, result.get().action_parameters().title_fade_in());
    }

    @Test
    @DisplayName("GIVE_XP JSON 解析")
    void giveXpParsed() {
        String json = """
            {"dialogue":"给你经验","action_type":"GIVE_XP","action_parameters":{"xp_amount":100},"emotion_change":"NONE"}""";
        var result = parser.parse(json);
        assertTrue(result.isPresent());
        assertEquals("GIVE_XP", result.get().action_type());
        assertEquals(100, result.get().action_parameters().xp_amount());
    }

    @Test
    @DisplayName("PLAY_SOUND JSON 解析")
    void playSoundParsed() {
        String json = """
            {"dialogue":"听这个","action_type":"PLAY_SOUND","action_parameters":{"sound_name":"ENTITY_VILLAGER_HAPPY","sound_volume":1.0,"sound_pitch":1.5},"emotion_change":"UPGRADE"}""";
        var result = parser.parse(json);
        assertTrue(result.isPresent());
        assertEquals("PLAY_SOUND", result.get().action_type());
        assertEquals("ENTITY_VILLAGER_HAPPY", result.get().action_parameters().sound_name());
        assertEquals(1.5, result.get().action_parameters().sound_pitch());
        assertEquals("UPGRADE", result.get().emotion_change());
    }

    // ---- Markdown 代码块剥离 ----

    @Test
    @DisplayName("Markdown ```json 包裹的 JSON 正常解析")
    void markdownWrappedParsed() {
        String wrapped = "```json\n{\"dialogue\":\"你好\",\"action_type\":\"NONE\"}\n```";
        var result = parser.parse(wrapped);
        assertTrue(result.isPresent());
        assertEquals("你好", result.get().dialogue());
    }

    @Test
    @DisplayName("Markdown ``` 包裹（无 json 标记）正常解析")
    void markdownNoJsonTagParsed() {
        String wrapped = "```\n{\"dialogue\":\"测试\",\"action_type\":\"NONE\"}\n```";
        var result = parser.parse(wrapped);
        assertTrue(result.isPresent());
        assertEquals("测试", result.get().dialogue());
    }

    // ---- 纯文本兜底 ----

    @Test
    @DisplayName("纯文本非 JSON 触发兜底，包装为 NONE")
    void plaintextFallback() {
        String plaintext = "旅行者，你好。今天天气不错。";
        var result = parser.parse(plaintext);
        assertTrue(result.isPresent());
        assertEquals("NONE", result.get().action_type());
        assertTrue(result.get().dialogue().contains("旅行者"));
    }

    @Test
    @DisplayName("纯文本超过 200 字符被截断")
    void plaintextTruncated() {
        String longText = "A".repeat(250);
        var result = parser.parse(longText);
        assertTrue(result.isPresent());
        assertTrue(result.get().dialogue().length() <= 200);
        assertTrue(result.get().dialogue().endsWith("..."));
    }

    // ---- 空/null 输入 ----

    @Test
    @DisplayName("null 输入返回 empty")
    void nullInputReturnsEmpty() {
        var result = parser.parse(null);
        assertTrue(result.response().isEmpty());
    }

    @Test
    @DisplayName("纯空白输入返回 empty")
    void blankInputReturnsEmpty() {
        var result = parser.parse("   \n  \t  ");
        assertTrue(result.response().isEmpty());
    }

    // ---- 无效 JSON ----

    @Test
    @DisplayName("以 { 开头但 JSON 格式错误返回 empty")
    void malformedJsonReturnsEmpty() {
        String bad = "{\"dialogue\": broken json";
        var result = parser.parse(bad);
        assertTrue(result.response().isEmpty());
    }

    @Test
    @DisplayName("JSON 缺少 dialogue 字段返回 empty")
    void missingDialogueField() {
        String json = "{\"action_type\":\"NONE\"}";
        var result = parser.parse(json);
        assertTrue(result.response().isEmpty());
    }

    @Test
    @DisplayName("dialogue 为空字符串返回 empty")
    void emptyDialogueField() {
        String json = "{\"dialogue\":\"\",\"action_type\":\"NONE\"}";
        var result = parser.parse(json);
        assertTrue(result.response().isEmpty());
    }

    // ---- hasAction() 行为 ----

    @Test
    @DisplayName("hasAction() 在 action_type=NONE 时返回 false")
    void hasActionNoneReturnsFalse() {
        LLMResponse resp = new LLMResponse("hi", "NONE", null, null);
        assertFalse(resp.hasAction());
    }

    @Test
    @DisplayName("hasAction() 在 action_type=GIVE_ITEM 时返回 true")
    void hasActionGiveItemReturnsTrue() {
        LLMResponse resp = new LLMResponse("hi", "GIVE_ITEM", null, null);
        assertTrue(resp.hasAction());
    }

    // ---- emotion_change 解析 ----

    @Test
    @DisplayName("emotion_change 字段正确解析")
    void emotionChangeParsed() {
        String json = """
            {"dialogue":"感动","action_type":"NONE","emotion_change":"UPGRADE"}""";
        var result = parser.parse(json);
        assertTrue(result.isPresent());
        assertEquals("UPGRADE", result.get().emotion_change());
        assertEquals(com.agenticnpc.emotion.EmotionChange.UPGRADE,
            result.get().getEmotionChange());
    }

    // ---- JSON 提取（前缀/后缀文本污染）----

    @Nested
    @DisplayName("JSON 提取：前缀/后缀文本")
    class JsonExtraction {

        @Test
        @DisplayName("前缀角色文本 + JSON → 正确提取")
        void prefixTextThenJson() {
            String input = "（略微打量了你一眼）\n{\"dialogue\":\"你好旅行者\",\"action_type\":\"NONE\"}";
            var result = parser.parse(input);
            assertTrue(result.isPresent());
            assertEquals("你好旅行者", result.get().dialogue());
        }

        @Test
        @DisplayName("JSON + 后缀文本 → 正确提取")
        void jsonThenSuffixText() {
            String input = "{\"dialogue\":\"再见\",\"action_type\":\"NONE\"}\n祝你旅途愉快。";
            var result = parser.parse(input);
            assertTrue(result.isPresent());
            assertEquals("再见", result.get().dialogue());
        }

        @Test
        @DisplayName("前缀 + JSON + 后缀 → 正确提取")
        void prefixJsonSuffix() {
            String input = "嗯...\n{\"dialogue\":\"好的\",\"action_type\":\"GIVE_ITEM\",\"action_parameters\":{\"item_id\":\"BREAD\",\"amount\":1}}\n希望对你有帮助。";
            var result = parser.parse(input);
            assertTrue(result.isPresent());
            assertEquals("GIVE_ITEM", result.get().action_type());
        }

        @Test
        @DisplayName("Markdown + 前缀文本 + JSON → 正确提取")
        void markdownWithPrefixJson() {
            String input = "```json\n（微笑）\n{\"dialogue\":\"你好啊\",\"action_type\":\"NONE\"}\n```";
            var result = parser.parse(input);
            assertTrue(result.isPresent());
            assertEquals("你好啊", result.get().dialogue());
        }

        @Test
        @DisplayName("嵌套 JSON 对象正确提取")
        void nestedJsonObject() {
            String input = "角色描写\n{\"dialogue\":\"给你\",\"action_type\":\"GIVE_ITEM\",\"action_parameters\":{\"item_id\":\"DIAMOND\",\"amount\":1}}";
            var result = parser.parse(input);
            assertTrue(result.isPresent());
            assertEquals("DIAMOND", result.get().action_parameters().item_id());
        }

        @Test
        @DisplayName("dialogue 中的花括号不影响提取")
        void dialogueWithBraces() {
            String input = "{\"dialogue\":\"坐标是{100,64}\",\"action_type\":\"NONE\"}";
            var result = parser.parse(input);
            assertTrue(result.isPresent());
            assertTrue(result.get().dialogue().contains("{100,64}"));
        }

        @Test
        @DisplayName("无 JSON 内容走纯文本兜底")
        void noJsonFallsBackToPlaintext() {
            String input = "旅行者你好，今天天气不错。";
            var result = parser.parse(input);
            assertTrue(result.isPresent());
            assertEquals("NONE", result.get().action_type());
            assertTrue(result.get().dialogue().contains("旅行者"));
        }

        @Test
        @DisplayName("extractFirstJsonObject 返回 null 当无 JSON")
        void extractReturnsNull() {
            assertNull(parser.extractFirstJsonObject("no json here"));
            assertNull(parser.extractFirstJsonObject(null));
            assertNull(parser.extractFirstJsonObject(""));
        }
    }
}
