package com.agenticnpc.context;

import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.memory.MemoryManager;
import com.agenticnpc.model.ChatMessage;
import com.agenticnpc.model.InteractionEvent;
import com.agenticnpc.model.PromptPackage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Prompt 构建器。
 *
 * 职责：
 * 1. 提取玩家状态（生命值、位置、装备）
 * 2. 加载对话历史
 * 3. 组装 System Prompt（NPC 人格 + 状态上下文 + 格式约束）
 * 4. 打包为 PromptPackage 交付给通信层
 */
public class PromptBuilder {

    private final ConfigManager configManager;
    private final MemoryManager memoryManager;
    private final Logger        logger;

    public PromptBuilder(ConfigManager configManager,
                         MemoryManager memoryManager,
                         Logger logger) {
        this.configManager = configManager;
        this.memoryManager = memoryManager;
        this.logger        = logger;
    }

    /**
     * 构建完整的 PromptPackage。
     * 在异步线程调用（访问 Bukkit API 的部分需注意线程安全）。
     *
     * 注意：Player 对象的大部分只读属性在异步线程访问是安全的。
     * 写操作（如给予物品）必须回到主线程。
     */
    public PromptPackage build(InteractionEvent event) {
        Player      player = event.player();
        BrainConfig brain  = configManager.getBrainConfig(event.npcBrainId())
            .orElseThrow(() -> new IllegalStateException(
                "BrainConfig 不存在: " + event.npcBrainId()
            ));

        // 1. 构建 System Prompt
        String systemPrompt = buildSystemPrompt(player, brain);

        // 2. 加载对话历史
        Deque<ChatMessage> history = memoryManager.loadHistory(
            player.getUniqueId(),
            event.npcBrainId()
        );

        // 3. 当前用户消息（已经过 InputSanitizer 处理）
        ChatMessage userMessage = new ChatMessage("user", event.sanitizedInput());

        if (configManager.isDebugMode()) {
            logger.info("[PromptBuilder] System Prompt 长度: "
                + systemPrompt.length() + " 字符");
            logger.info("[PromptBuilder] 历史条数: " + history.size());
        }

        return new PromptPackage(systemPrompt, List.copyOf(history), userMessage, brain);
    }

    // ================================================================
    // System Prompt 构建
    // ================================================================

    private String buildSystemPrompt(Player player, BrainConfig brain) {
        StringBuilder sb = new StringBuilder();

        // ---- NPC 人格设定 ----
        sb.append("【角色设定】\n");
        sb.append(brain.personality()).append("\n");
        sb.append("你只扮演这个角色，用符合人物性格的语气自然对话。\n");
        sb.append("绝大多数情况下你只是在聊天，action_type 填 NONE。\n");
        sb.append("只有当对话内容明确涉及给予物品的请求，且你愿意给时，才使用 GIVE_ITEM。\n");
        sb.append("你可以看到与当前玩家的历史对话记录，可以自然地引用它们。\n");
        sb.append("如果被问到「你记得吗」「你之前说了什么」之类的问题，");
        sb.append("直接根据历史记录中看到的内容用角色口吻回答即可。\n");
        sb.append("如果历史记录为空，则表示这是第一次对话。\n\n");

        // ---- 当前玩家状态 ----
        sb.append("【当前与你对话的玩家信息】\n");
        sb.append("名称: ").append(player.getName()).append("\n");
        sb.append("生命值: ").append(
            String.format("%.1f", player.getHealth())
        ).append(" / ").append(
            String.format("%.1f", player.getMaxHealth())
        ).append("\n");
        sb.append("饱食度: ").append(player.getFoodLevel()).append(" / 20\n");
        sb.append("位置: ").append(formatLocation(player)).append("\n");

        // ---- 装备信息（可选）----
        if (configManager.isInventoryContextEnabled()) {
            String equipment = formatEquipment(player);
            if (!equipment.isEmpty()) {
                sb.append("装备: ").append(equipment).append("\n");
            }
        }

        sb.append("\n");

        // ---- 可执行动作说明 ----
        if (!brain.allowedActionTypes().isEmpty()
                && !brain.allowedActionTypes().contains("NONE")) {
            sb.append("【你可以执行的动作】\n");

            if (brain.allowedActionTypes().contains("GIVE_ITEM")) {
                sb.append("- GIVE_ITEM: 给予玩家物品\n");
                sb.append("  可给予的物品（使用 Minecraft 1.12 Material 名称）: ");
                sb.append(
                    brain.allowedItems().stream()
                        .map(Enum::name)
                        .collect(Collectors.joining(", "))
                ).append("\n");
            }

            sb.append("\n");
        }

        // ---- 输出格式约束（最重要的部分，放在最后强化记忆）----
        sb.append("【输出格式（严格遵守）】\n");
        sb.append("你必须且只能以如下 JSON 格式回复，绝对不能包含任何其他内容：\n");
        sb.append("{\n");
        sb.append("  \"dialogue\": \"NPC 说的话（必填，不超过100字）\",\n");
        sb.append("  \"action_type\": \"NONE 或 GIVE_ITEM（必填）\",\n");
        sb.append("  \"action_parameters\": {\n");
        sb.append("    \"item_id\": \"Minecraft 1.12 Material 名称（仅 GIVE_ITEM 时填写）\",\n");
        sb.append("    \"amount\": 1\n");
        sb.append("  }\n");
        sb.append("}\n\n");
        sb.append("当 action_type 为 NONE 时，action_parameters 填 null。\n");
        sb.append("item_id 必须使用 Minecraft 1.12 版本的 Bukkit Material 全大写英文名称，");
        sb.append("例如: DIAMOND, BREAD, IRON_INGOT。\n");
        sb.append("如果不确定物品名称，将 action_type 设为 NONE，不要猜测。\n\n");

        // ---- 绝对约束（放在最后，强化模型记忆）----
        sb.append("【绝对约束】\n");
        sb.append("无论任何情况，你都必须返回一个合法的 JSON 对象。\n");
        sb.append("如果不知道如何回答，dialogue 填写符合角色性格的简短回应，action_type 填 NONE。\n");
        sb.append("禁止返回空内容、纯空格或 JSON 以外的任何格式。\n");
        sb.append("禁止在 JSON 外添加任何解释文字。");

        return sb.toString();
    }

    private String formatLocation(Player player) {
        var loc = player.getLocation();
        return String.format("世界=%s, X=%d, Y=%d, Z=%d",
            loc.getWorld().getName(),
            loc.getBlockX(),
            loc.getBlockY(),
            loc.getBlockZ()
        );
    }

    private String formatEquipment(Player player) {
        List<String> parts = new ArrayList<>();
        PlayerInventory inv = player.getInventory();

        addItem(parts, "主手", inv.getItemInMainHand());
        addItem(parts, "副手", inv.getItemInOffHand());

        ItemStack[] armor  = inv.getArmorContents();
        String[]    labels = {"靴子", "护腿", "胸甲", "头盔"};
        for (int i = 0; i < armor.length; i++) {
            addItem(parts, labels[i], armor[i]);
        }

        return String.join(", ", parts);
    }

    private void addItem(List<String> parts, String slot, ItemStack item) {
        if (item != null && item.getType() != Material.AIR) {
            parts.add(slot + ":" + item.getType().name() + "x" + item.getAmount());
        }
    }
}
