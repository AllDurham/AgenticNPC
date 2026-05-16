package com.agenticnpc.context;

import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.emotion.EmotionLevel;
import com.agenticnpc.emotion.EmotionManager;
import com.agenticnpc.memory.CompressionService;
import com.agenticnpc.memory.MemoryManager;
import com.agenticnpc.memory.PlayerProfileManager;
import com.agenticnpc.model.InteractionEvent;
import com.agenticnpc.model.PromptPackage;
import com.agenticnpc.model.PromptTokenBreakdown;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromptVariantTest {

    private static final String BRAIN_ID = "test_brain";
    private static final String PERSONALITY = "你是一个神秘的旅行者，走遍了大陆的每个角落。你说话简短有深度。";

    private final BrainConfig brain = new BrainConfig(
        BRAIN_ID, "测试NPC", PERSONALITY, "命运的齿轮暂时停止了转动...",
        Set.of("GIVE_ITEM", "TELEPORT"),
        Set.of(Material.DIAMOND, Material.BREAD),
        Set.of("HEAL"),
        "ENTITY_VILLAGER_AMBIENT", 1.0f, 1.0f, true,
        "brain", false, "NEUTRAL",
        Set.of("ENTITY_VILLAGER_AMBIENT")
    );

    private ConfigManager mockConfig(String variant) {
        ConfigManager cm = mock(ConfigManager.class);
        when(cm.getPromptVariant()).thenReturn(variant);
        when(cm.getBrainConfig(BRAIN_ID)).thenReturn(Optional.of(brain));
        when(cm.isEmotionEnabled()).thenReturn(true);
        when(cm.isInventoryContextEnabled()).thenReturn(false);
        when(cm.isDebugMode()).thenReturn(false);
        return cm;
    }

    private PromptBuilder createBuilder(String variant) {
        ConfigManager cm = mockConfig(variant);

        MemoryManager memoryManager = mock(MemoryManager.class);
        when(memoryManager.loadHistory(any(), anyString())).thenReturn(new ArrayDeque<>());
        when(memoryManager.getRepository()).thenReturn(null);

        CompressionService compressionService = mock(CompressionService.class);
        when(compressionService.loadSummary(any(), anyString(), any())).thenReturn(Optional.empty());

        PlayerProfileManager profileManager = mock(PlayerProfileManager.class);
        when(profileManager.getProfile(any(), anyString())).thenReturn(Optional.empty());

        EmotionManager emotionManager = mock(EmotionManager.class);
        when(emotionManager.getEmotion(any(), any())).thenReturn(EmotionLevel.NEUTRAL);

        return new PromptBuilder(cm, memoryManager, compressionService,
            profileManager, emotionManager, Logger.getAnonymousLogger());
    }

    private Player mockPlayer() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Steve");
        when(player.getHealth()).thenReturn(20.0);
        when(player.getMaxHealth()).thenReturn(20.0);
        when(player.getFoodLevel()).thenReturn(18);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        Location loc = mock(Location.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(loc.getWorld()).thenReturn(world);
        when(loc.getBlockX()).thenReturn(100);
        when(loc.getBlockY()).thenReturn(64);
        when(loc.getBlockZ()).thenReturn(200);
        when(player.getLocation()).thenReturn(loc);

        PlayerInventory inv = mock(PlayerInventory.class);
        when(inv.getItemInMainHand()).thenReturn(new ItemStack(Material.AIR));
        when(inv.getItemInOffHand()).thenReturn(new ItemStack(Material.AIR));
        when(inv.getArmorContents()).thenReturn(new ItemStack[4]);
        when(player.getInventory()).thenReturn(inv);

        return player;
    }

    @Test
    @DisplayName("current variant 生成有效 PromptPackage")
    void currentVariantProducesValidPrompt() {
        PromptBuilder builder = createBuilder("current");
        Player player = mockPlayer();
        Entity npc = mock(Entity.class);
        InteractionEvent event = InteractionEvent.create(player, npc, BRAIN_ID, "你好");

        PromptPackage pkg = builder.build(event);
        assertNotNull(pkg);
        assertNotNull(pkg.systemPrompt());
        assertTrue(pkg.systemPrompt().contains(PERSONALITY));
        assertTrue(pkg.systemPrompt().contains("【角色设定】"));
    }

    @Test
    @DisplayName("slim-v1 variant 生成有效 PromptPackage")
    void slimV1ProducesValidPrompt() {
        PromptBuilder builder = createBuilder("slim-v1");
        Player player = mockPlayer();
        Entity npc = mock(Entity.class);
        InteractionEvent event = InteractionEvent.create(player, npc, BRAIN_ID, "你好");

        PromptPackage pkg = builder.build(event);
        assertNotNull(pkg);
        assertNotNull(pkg.systemPrompt());
        assertTrue(pkg.systemPrompt().contains(PERSONALITY));
    }

    @Test
    @DisplayName("slim-v1 token 数量低于 current")
    void slimV1UsesFewerTokens() {
        Player player = mockPlayer();
        Entity npc = mock(Entity.class);
        InteractionEvent event = InteractionEvent.create(player, npc, BRAIN_ID, "你好");

        PromptPackage currentPkg = createBuilder("current").build(event);
        PromptPackage slimPkg = createBuilder("slim-v1").build(event);

        int currentTokens = PromptBuilder.estimateTokens(currentPkg.systemPrompt());
        int slimTokens = PromptBuilder.estimateTokens(slimPkg.systemPrompt());

        assertTrue(slimTokens < currentTokens,
            "slim-v1 (" + slimTokens + ") 应少于 current (" + currentTokens + ")");
        double reduction = 1.0 - (double) slimTokens / currentTokens;
        assertTrue(reduction > 0.10,
            "token 减少应 > 10%, 实际: " + String.format("%.1f%%", reduction * 100));
    }

    @Test
    @DisplayName("slim-v1 保留 personality 文本")
    void slimV1PreservesPersonality() {
        Player player = mockPlayer();
        Entity npc = mock(Entity.class);
        InteractionEvent event = InteractionEvent.create(player, npc, BRAIN_ID, "你好");

        PromptPackage pkg = createBuilder("slim-v1").build(event);
        assertTrue(pkg.systemPrompt().contains(PERSONALITY),
            "slim-v1 应保留 personality 原文");
    }

    @Test
    @DisplayName("slim-v1 保留 action JSON key 名")
    void slimV1PreservesActionKeys() {
        Player player = mockPlayer();
        Entity npc = mock(Entity.class);
        InteractionEvent event = InteractionEvent.create(player, npc, BRAIN_ID, "你好");

        String prompt = createBuilder("slim-v1").build(event).systemPrompt();
        assertTrue(prompt.contains("GIVE_ITEM"), "slim-v1 应包含 GIVE_ITEM");
        assertTrue(prompt.contains("TELEPORT"), "slim-v1 应包含 TELEPORT");
        assertTrue(prompt.contains("\"item_id\""), "slim-v1 应保留 JSON key: item_id");
        assertTrue(prompt.contains("\"amount\""), "slim-v1 应保留 JSON key: amount");
        assertTrue(prompt.contains("\"world\""), "slim-v1 应保留 JSON key: world");
    }

    @Test
    @DisplayName("未知 variant 兜底为 current 行为")
    void unknownVariantDefaultsToCurrent() {
        Player player = mockPlayer();
        Entity npc = mock(Entity.class);
        InteractionEvent event = InteractionEvent.create(player, npc, BRAIN_ID, "你好");

        int currentTokens = PromptBuilder.estimateTokens(
            createBuilder("current").build(event).systemPrompt());
        int unknownTokens = PromptBuilder.estimateTokens(
            createBuilder("unknown-variant").build(event).systemPrompt());

        assertEquals(currentTokens, unknownTokens,
            "未知 variant 应与 current 产出相同 token 数");
    }

    @Test
    @DisplayName("slim-v1 fixedCost 仅含 format + actions")
    void slimV1FixedCostExcludesDynamic() {
        Player player = mockPlayer();
        Entity npc = mock(Entity.class);
        InteractionEvent event = InteractionEvent.create(player, npc, BRAIN_ID, "你好");

        var result = createBuilder("slim-v1").buildWithBreakdown(event);
        PromptTokenBreakdown bd = result.breakdown();

        int fixedCost = bd.fixedCost();
        assertEquals(bd.formatTokens() + bd.actionTokens(), fixedCost);
        // fixedCost should NOT include system, profile, summary, emotion, playerInfo, history, input
        assertTrue(fixedCost < bd.totalTokens(),
            "fixedCost (" + fixedCost + ") 应小于 totalTokens (" + bd.totalTokens() + ")");
    }
}
