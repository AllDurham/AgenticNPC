package com.agenticnpc.executor;

import com.agenticnpc.audit.AuditLogger;
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

import com.agenticnpc.gateway.InputSanitizer;

import java.util.HashMap;
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

    /**
     * Sound 别名映射表（1.13+ → 1.12.2）。
     * 仅覆盖 1.13 "扁平化" 中被重命名的少量音效。
     * 参考 ItemSafetyGuard.ALIAS_MAP 模式。
     */
    private static final Map<String, String> SOUND_ALIAS_MAP = new HashMap<>();
    static {
        SOUND_ALIAS_MAP.put("BLOCK_NOTE_BLOCK_HARP",  "BLOCK_NOTE_HARP");
        SOUND_ALIAS_MAP.put("BLOCK_NOTE_BLOCK_BASS",  "BLOCK_NOTE_BASS");
        SOUND_ALIAS_MAP.put("BLOCK_NOTE_BLOCK_SNARE", "BLOCK_NOTE_SNARE");
        SOUND_ALIAS_MAP.put("BLOCK_NOTE_BLOCK_PLING", "BLOCK_NOTE_PLING");
        SOUND_ALIAS_MAP.put("BLOCK_NOTE_BLOCK_HAT",   "BLOCK_NOTE_HAT");
        SOUND_ALIAS_MAP.put("AMBIENT_CAVE",           "AMBIENCE_CAVE");
        SOUND_ALIAS_MAP.put("AMBIENT_WEATHER_RAIN",   "WEATHER_RAIN");
        SOUND_ALIAS_MAP.put("AMBIENT_WEATHER_THUNDER","WEATHER_THUNDER");
        SOUND_ALIAS_MAP.put("ENTITY_PLAYER_LEVEL_UP", "ENTITY_PLAYER_LEVELUP");
    }

    private final ConfigManager  config;
    private final TeleportGuard  teleportGuard;
    private final PotionGuard    potionGuard;
    private final Logger         logger;
    private AuditLogger          auditLogger;

    public ActionExecutor(ConfigManager config,
                          TeleportGuard teleportGuard,
                          PotionGuard potionGuard,
                          Logger logger) {
        this.config        = config;
        this.teleportGuard = teleportGuard;
        this.potionGuard   = potionGuard;
        this.logger        = logger;
    }

    public void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
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
            case SEND_TITLE:
                executeSendTitle(player, validation.parameters(), brain);
                break;
            case PLAY_SOUND:
                executePlaySound(player, validation.parameters(), brain);
                break;
            case GIVE_XP:
                executeGiveXp(player, validation.parameters(), brain);
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

    // ================================================================
    // SEND_TITLE（标题发送）
    // ================================================================

    private void executeSendTitle(Player player, com.agenticnpc.model.ActionParameters params, BrainConfig brain) {
        if (params == null || params.title_text() == null || params.title_text().isBlank()) {
            logger.warning("[ActionExecutor] SEND_TITLE 参数不完整，跳过");
            return;
        }

        // 截断标题长度
        String title = params.title_text();
        if (title.length() > InputSanitizer.MAX_TITLE_LENGTH) {
            title = title.substring(0, InputSanitizer.MAX_TITLE_LENGTH);
        }

        String subtitle = params.title_subtitle();
        if (subtitle != null && subtitle.length() > InputSanitizer.MAX_SUBTITLE_LENGTH) {
            subtitle = subtitle.substring(0, InputSanitizer.MAX_SUBTITLE_LENGTH);
        }

        // 剥离截断后可能遗留的孤立 § 颜色代码标记
        title = stripOrphanColorCode(title);
        if (subtitle != null) subtitle = stripOrphanColorCode(subtitle);

        // 截断时长参数到 [0, 200]
        int fadeIn  = clampInt(params.title_fade_in()  != null ? params.title_fade_in()  : 10, 0, 200);
        int stay    = clampInt(params.title_stay()     != null ? params.title_stay()     : 60, 0, 200);
        int fadeOut = clampInt(params.title_fade_out() != null ? params.title_fade_out() : 20, 0, 200);

        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);

        logger.info(String.format(
            "[ActionExecutor] SEND_TITLE | 玩家: %s | 主标题: %s | 副标题: %s | Brain: %s",
            player.getName(), title, subtitle != null ? subtitle : "-", brain.id()));

        if (auditLogger != null) {
            auditLogger.logDialogueSuccess(
                player.getUniqueId(), player.getName(), brain.id(),
                "SEND_TITLE: " + title + " / " + (subtitle != null ? subtitle : "-"),
                null, ActionType.SEND_TITLE, null);
        }
    }

    // ================================================================
    // PLAY_SOUND（音效播放，含别名映射）
    // ================================================================

    private void executePlaySound(Player player, com.agenticnpc.model.ActionParameters params, BrainConfig brain) {
        if (params == null || params.sound_name() == null || params.sound_name().isBlank()) {
            logger.warning("[ActionExecutor] PLAY_SOUND 参数不完整，跳过");
            return;
        }

        String soundName = params.sound_name().toUpperCase().trim();

        // 音量 / 音调截断
        float volume = (float) clampDouble(
            params.sound_volume() != null ? params.sound_volume() : 1.0, 0.0, 2.0);
        float pitch  = (float) clampDouble(
            params.sound_pitch()  != null ? params.sound_pitch()  : 1.0, 0.5, 2.0);

        // 尝试解析 Sound 枚举（含别名映射回退）
        Sound sound = resolveSound(soundName);
        if (sound == null) {
            logger.warning("[ActionExecutor] PLAY_SOUND 枚举不存在，静默跳过: " + soundName);
            return;
        }

        player.playSound(player.getLocation(), sound, volume, pitch);

        logger.info(String.format(
            "[ActionExecutor] PLAY_SOUND | 玩家: %s | 音效: %s | 音量: %.1f | 音调: %.1f | Brain: %s",
            player.getName(), soundName, volume, pitch, brain.id()));

        if (auditLogger != null) {
            auditLogger.logDialogueSuccess(
                player.getUniqueId(), player.getName(), brain.id(),
                "PLAY_SOUND: " + soundName + " vol=" + volume + " pitch=" + pitch,
                null, ActionType.PLAY_SOUND, soundName);
        }
    }

    /**
     * 解析 Sound 枚举名，支持 1.13+ → 1.12.2 别名映射回退。
     */
    private Sound resolveSound(String name) {
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException e) {
            // 别名映射回退
            String alias = SOUND_ALIAS_MAP.get(name);
            if (alias != null) {
                try {
                    return Sound.valueOf(alias);
                } catch (IllegalArgumentException ignored) {}
            }
            return null;
        }
    }

    // ================================================================
    // GIVE_XP（经验值给予）
    // ================================================================

    private void executeGiveXp(Player player, com.agenticnpc.model.ActionParameters params, BrainConfig brain) {
        if (params == null || params.xp_amount() == null || params.xp_amount() <= 0) {
            logger.warning("[ActionExecutor] GIVE_XP 参数无效，跳过");
            return;
        }

        int requested = params.xp_amount();
        int maxXp = config.getXpMaxPerAction();
        int actual = Math.min(requested, maxXp);
        boolean truncated = actual < requested;

        player.giveExp(actual);

        if (truncated) {
            logger.info(String.format(
                "[ActionExecutor] GIVE_XP（已截断）| 玩家: %s | 请求: %d | 实际: %d | Brain: %s",
                player.getName(), requested, actual, brain.id()));
            player.sendMessage("§7[你获得了 " + actual + " 点经验值（原始请求 " + requested + " 已截断到上限 " + maxXp + "）]");
        } else {
            logger.info(String.format(
                "[ActionExecutor] GIVE_XP | 玩家: %s | 数量: %d | Brain: %s",
                player.getName(), actual, brain.id()));
            player.sendMessage("§7[你获得了 " + actual + " 点经验值]");
        }

        if (auditLogger != null) {
            String detail = truncated
                ? "GIVE_XP: " + requested + " -> " + actual + "（截断到上限 " + maxXp + "）"
                : "GIVE_XP: " + actual;
            auditLogger.logDialogueSuccess(
                player.getUniqueId(), player.getName(), brain.id(),
                detail, null, ActionType.GIVE_XP,
                truncated ? requested + "->" + actual : String.valueOf(actual));
        }
    }

    // ================================================================
    // 工具方法
    // ================================================================

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 剥离字符串末尾孤立的 § 颜色代码标记。
     * 截断可能导致 "§e你好" 被截为 "§e你"（安全），
     * 但 "§" 本身被截为 "§"（孤立，Minecraft 会吞掉下一个字符）。
     * 同时剥离末尾不完整的 "§X" 模式（§ 后无有效字符的情况极少但防御性处理）。
     */
    private static String stripOrphanColorCode(String s) {
        if (s == null || s.isEmpty()) return s;
        // 末尾孤立 §
        if (s.endsWith("§")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
