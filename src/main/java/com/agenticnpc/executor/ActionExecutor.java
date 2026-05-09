package com.agenticnpc.executor;

import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.dispatch.AsyncDispatcher.ExecutionCallback;
import com.agenticnpc.gateway.model.ItemSafetyResult;
import com.agenticnpc.gateway.model.ValidationResult;
import com.agenticnpc.guard.PotionGuard;
import com.agenticnpc.guard.TeleportGuard;
import com.agenticnpc.model.ActionType;
import com.agenticnpc.model.LLMResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
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

    private final ConfigManager  config;
    private final TeleportGuard  teleportGuard;
    private final PotionGuard    potionGuard;
    private final Logger         logger;

    public ActionExecutor(ConfigManager config,
                          TeleportGuard teleportGuard,
                          PotionGuard potionGuard,
                          Logger logger) {
        this.config        = config;
        this.teleportGuard = teleportGuard;
        this.potionGuard   = potionGuard;
        this.logger        = logger;
    }

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

    private void renderDialogue(Player player, String dialogue, BrainConfig brain) {
        if (dialogue == null || dialogue.isBlank()) return;

        String safe = dialogue.length() > 200
            ? dialogue.substring(0, 197) + "..." : dialogue;

        player.sendMessage("§e[" + brain.name() + "] §f" + safe);

        if (brain.actionBarEnabled() && config.isActionBarEnabled()) {
            player.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                new TextComponent("§e" + brain.name() + " §7> §f" + safe)
            );
        }

        if (config.isSoundEnabled(brain.id())) {
            playSoundSafe(player, brain);
        }
    }

    // ================================================================
    // 物品给予（纯净 ItemStack，零 NBT）
    // ================================================================

    private void executeGiveItem(Player player,
                                  ItemSafetyResult itemResult,
                                  BrainConfig brain) {
        ItemStack item = new ItemStack(itemResult.material(), itemResult.amount());
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);

        if (!overflow.isEmpty()) {
            Location dropLoc = player.getLocation();
            overflow.values().forEach(dropped ->
                player.getWorld().dropItemNaturally(dropLoc, dropped)
            );
            player.sendMessage("§7[背包已满，物品掉落在你脚下]");
        }

        logger.info(String.format(
            "[ActionExecutor] 给予物品 | 玩家: %s | 物品: %s x%d | Brain: %s",
            player.getName(), itemResult.material().name(), itemResult.amount(), brain.id()));
    }

    // ================================================================
    // 传送（含 WorldGuard 领地检查）
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

        // Y 坐标安全检查
        if (target.getY() < -64 || target.getY() > 320) {
            logger.warning("[ActionExecutor] TELEPORT Y 坐标越界: " + target.getY());
            player.sendMessage("§7[传送失败，坐标异常]");
            return;
        }

        // WorldGuard 领地检查
        TeleportGuard.TeleportCheckResult checkResult = teleportGuard.check(target);
        if (!checkResult.allowed()) {
            logger.warning(String.format(
                "[ActionExecutor] TELEPORT 被 WorldGuard 拦截 | 玩家: %s | 原因: %s",
                player.getName(), checkResult.reason()));
            player.sendMessage("§7[传送失败] " + checkResult.reason());
            return;
        }

        player.teleport(target);
        player.sendMessage("§7[你被传送到了 " + params.world() + " "
            + params.x().intValue() + ", " + params.y().intValue() + ", " + params.z().intValue() + "]");
        logger.info(String.format("[ActionExecutor] 传送 | 玩家: %s | 目标: %s %.0f,%.0f,%.0f | Brain: %s",
            player.getName(), params.world(), params.x(), params.y(), params.z(), brain.id()));
    }

    // ================================================================
    // 药水效果（含白名单检查）
    // ================================================================

    private void executeGiveEffect(Player player, com.agenticnpc.model.ActionParameters params, BrainConfig brain) {
        if (params == null || params.effect_name() == null || params.duration_seconds() == null || params.amplifier() == null) {
            logger.warning("[ActionExecutor] GIVE_EFFECT 参数不完整，跳过");
            return;
        }

        PotionGuard.PotionCheckResult checkResult = potionGuard.check(
            params.effect_name(), params.duration_seconds(), params.amplifier(), brain.id());

        if (!checkResult.safe()) {
            logger.warning("[ActionExecutor] GIVE_EFFECT 被拦截: " + checkResult.reason());
            player.sendMessage("§7[效果施加失败] " + checkResult.reason());
            return;
        }

        player.addPotionEffect(new PotionEffect(checkResult.type(), checkResult.durationTicks(), checkResult.amplifier()));
        player.sendMessage("§7[你获得了 " + params.effect_name().toLowerCase() + " 效果]");
        logger.info(String.format("[ActionExecutor] 药水效果 | 玩家: %s | 效果: %s %ds Lv%d | Brain: %s",
            player.getName(), params.effect_name(), params.duration_seconds(), params.amplifier(), brain.id()));
    }

    // ================================================================
    // 跨版本安全音效
    // ================================================================

    private void playSoundSafe(Player player, BrainConfig brain) {
        String soundName = brain.dialogueSound();
        if (soundName == null || soundName.isBlank()) return;

        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), sound,
                brain.dialogueSoundVolume(), brain.dialogueSoundPitch());
        } catch (IllegalArgumentException e) {
            if (config.isDebugMode()) {
                logger.warning("[ActionExecutor] Sound 枚举不存在: " + soundName
                    + "（当前版本不支持，已跳过）");
            }
        }
    }
}
