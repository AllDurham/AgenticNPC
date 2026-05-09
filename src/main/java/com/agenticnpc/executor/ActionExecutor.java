package com.agenticnpc.executor;

import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.dispatch.AsyncDispatcher.ExecutionCallback;
import com.agenticnpc.gateway.model.ItemSafetyResult;
import com.agenticnpc.gateway.model.ValidationResult;
import com.agenticnpc.model.ActionType;
import com.agenticnpc.model.LLMResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.Map;
import java.util.logging.Logger;

/**
 * 动作执行器（Layer 5）。
 *
 * 铁律：本类所有方法必须在 Bukkit 主线程调用。
 *       由 AsyncDispatcher 通过 syncToMain() 保证。
 *
 * 禁止：Bukkit.dispatchCommand() / performCommand() / 任何命令字符串。
 * 实现：纯 Bukkit Java API。
 */
public class ActionExecutor implements ExecutionCallback {

    private final ConfigManager config;
    private final Logger        logger;

    public ActionExecutor(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /**
     * ExecutionCallback 实现入口。
     * 由 AsyncDispatcher 在主线程调用。
     */
    @Override
    public void execute(Player player,
                        LLMResponse response,
                        ValidationResult validation,
                        ItemSafetyResult itemSafetyResult,
                        BrainConfig brain) {
        // 1. 始终执行：发送 NPC 台词
        renderDialogue(player, response.dialogue(), brain);

        // 2. 执行附加动作
        switch (validation.actionType()) {
            case GIVE_ITEM:
                if (itemSafetyResult != null && itemSafetyResult.safe()) {
                    executeGiveItem(player, itemSafetyResult, brain);
                }
                break;
            case TELEPORT:
                executeTeleport(player, validation.parameters(), brain);
                break;
            case GIVE_EFFECT:
                executeGiveEffect(player, validation.parameters(), brain);
                break;
            default:
                break;
        }
    }

    // ================================================================
    // 台词渲染（三层）
    // ================================================================

    /**
     * 三层渲染：聊天框 + ActionBar + 音效。
     */
    private void renderDialogue(Player player, String dialogue, BrainConfig brain) {
        if (dialogue == null || dialogue.isBlank()) return;

        // 长度截断，防止超长文本刷屏
        String safe = dialogue.length() > 200
            ? dialogue.substring(0, 197) + "..."
            : dialogue;

        // Layer 1: 聊天框（始终执行）
        player.sendMessage("§e[" + brain.name() + "] §f" + safe);

        // Layer 2: ActionBar（可选）
        if (brain.actionBarEnabled() && config.isActionBarEnabled()) {
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                new TextComponent("§e" + brain.name() + " §7> §f" + safe)
            );
        }

        // Layer 3: 音效（可选，跨版本安全）
        if (config.isSoundEnabled(brain.id())) {
            playSoundSafe(player, brain);
        }
    }

    // ================================================================
    // 物品给予（纯净 ItemStack，零 NBT）
    // ================================================================

    /**
     * 给予物品。
     *
     * 核心安全设计：
     * - 使用 ItemSafetyGuard 已校验的 Material + amount
     * - new ItemStack(material, amount) 构造纯净物品，零自定义 NBT
     * - 从架构上消除 NBT 注入攻击面
     * - 背包已满时掉落在玩家脚下（原版行为）
     */
    private void executeGiveItem(Player player,
                                  ItemSafetyResult itemResult,
                                  BrainConfig brain) {
        // 纯净 ItemStack：仅材料 + 数量，无任何附加属性
        ItemStack item = new ItemStack(itemResult.material(), itemResult.amount());

        // addItem 返回无法放入背包的溢出物品
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);

        // 处理溢出：掉落在玩家当前位置
        if (!overflow.isEmpty()) {
            Location dropLoc = player.getLocation();
            overflow.values().forEach(dropped ->
                player.getWorld().dropItemNaturally(dropLoc, dropped)
            );
            player.sendMessage("§7[背包已满，物品掉落在你脚下]");
        }

        logger.info(String.format(
            "[ActionExecutor] 给予物品 | 玩家: %s | 物品: %s x%d | Brain: %s",
            player.getName(),
            itemResult.material().name(),
            itemResult.amount(),
            brain.id()
        ));
    }

    // ================================================================
    // 传送
    // ================================================================

    private void executeTeleport(Player player, com.agenticnpc.model.ActionParameters params, BrainConfig brain) {
        if (params == null || params.world() == null || params.x() == null || params.y() == null || params.z() == null) {
            logger.warning("[ActionExecutor] TELEPORT 参数不完整，跳过");
            return;
        }

        World world = Bukkit.getWorld(params.world());
        if (world == null) {
            logger.warning("[ActionExecutor] TELEPORT 目标世界不存在: " + params.world());
            player.sendMessage("§7[传送失败，目标世界不存在]");
            return;
        }

        Location target = new Location(world, params.x(), params.y(), params.z());

        // 安全检查：防止传送到虚空或基岩下方
        if (target.getY() < -64 || target.getY() > 320) {
            logger.warning("[ActionExecutor] TELEPORT Y 坐标越界: " + target.getY());
            player.sendMessage("§7[传送失败，坐标异常]");
            return;
        }

        player.teleport(target);
        player.sendMessage("§7[你被传送到了 " + params.world() + " " + params.x().intValue() + ", " + params.y().intValue() + ", " + params.z().intValue() + "]");
        logger.info(String.format("[ActionExecutor] 传送 | 玩家: %s | 目标: %s %.0f,%.0f,%.0f | Brain: %s",
            player.getName(), params.world(), params.x(), params.y(), params.z(), brain.id()));
    }

    // ================================================================
    // 药水效果
    // ================================================================

    private void executeGiveEffect(Player player, com.agenticnpc.model.ActionParameters params, BrainConfig brain) {
        if (params == null || params.effect_name() == null || params.duration_seconds() == null || params.amplifier() == null) {
            logger.warning("[ActionExecutor] GIVE_EFFECT 参数不完整，跳过");
            return;
        }

        PotionEffectType type = PotionEffectType.getByName(params.effect_name().toUpperCase());
        if (type == null) {
            logger.warning("[ActionExecutor] 未知药水效果: " + params.effect_name());
            player.sendMessage("§7[效果施加失败，未知效果类型]");
            return;
        }

        // 安全截断：最大 5 分钟，最大等级 5
        int duration = Math.min(Math.max(params.duration_seconds(), 1), 300) * 20;
        int amplifier = Math.min(Math.max(params.amplifier(), 0), 5);

        player.addPotionEffect(new PotionEffect(type, duration, amplifier));
        player.sendMessage("§7[你获得了 " + params.effect_name().toLowerCase() + " 效果]");
        logger.info(String.format("[ActionExecutor] 药水效果 | 玩家: %s | 效果: %s %ds Lv%d | Brain: %s",
            player.getName(), params.effect_name(), params.duration_seconds(), params.amplifier(), brain.id()));
    }

    // ================================================================
    // 跨版本安全音效
    // ================================================================

    /**
     * 跨版本安全播放音效。
     * Sound 枚举名在 1.13 时大规模重命名，使用运行时解析避免编译期绑定。
     * 解析失败时静默降级，不影响主流程。
     */
    private void playSoundSafe(Player player, BrainConfig brain) {
        String soundName = brain.dialogueSound();
        if (soundName == null || soundName.isBlank()) return;

        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(
                player.getLocation(),
                sound,
                brain.dialogueSoundVolume(),
                brain.dialogueSoundPitch()
            );
        } catch (IllegalArgumentException e) {
            if (config.isDebugMode()) {
                logger.warning("[ActionExecutor] Sound 枚举不存在: " + soundName
                    + "（当前版本不支持，已跳过）");
            }
        }
    }
}
