package com.agenticnpc.command;

import com.agenticnpc.audit.AuditLogger;
import com.agenticnpc.config.BrainConfig;
import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.emotion.EmotionLevel;
import com.agenticnpc.emotion.EmotionManager;
import com.agenticnpc.model.ActionType;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * /anpc emotion <player> <brainId> <emotionLevel>
 *
 * 管理员手动设置指定玩家对指定 Brain 的情绪档位。
 * 需要 agenticnpc.admin.emotion 权限。
 */
public class EmotionCommand {

    private final ConfigManager   config;
    private final EmotionManager  emotionManager;
    private final AuditLogger     auditLogger;

    public EmotionCommand(ConfigManager config, EmotionManager emotionManager, AuditLogger auditLogger) {
        this.config        = config;
        this.emotionManager = emotionManager;
        this.auditLogger   = auditLogger;
    }

    public void handle(CommandSender sender, String[] args) {
        // /anpc emotion <player> <brainId> <emotionLevel>
        if (args.length < 4) {
            sender.sendMessage("§c用法: /anpc emotion <玩家名> <brainId> <情绪档位>");
            sender.sendMessage("§7可用档位: " + Arrays.stream(EmotionLevel.values())
                .map(e -> e.name() + "(" + e.displayName + ")")
                .collect(Collectors.joining(", ")));
            return;
        }

        // 权限检查
        if (!sender.hasPermission("agenticnpc.admin.emotion")) {
            sender.sendMessage("§c你没有权限使用此命令（需要 agenticnpc.admin.emotion）");
            return;
        }

        String playerName = args[1];
        String brainId    = args[2];
        String emotionStr = args[3].toUpperCase().trim();

        // 目标玩家检查
        Player targetPlayer = Bukkit.getPlayerExact(playerName);
        if (targetPlayer == null) {
            sender.sendMessage("§c玩家不在线: §e" + playerName);
            return;
        }

        // Brain 检查
        BrainConfig brain = config.getBrainConfig(brainId).orElse(null);
        if (brain == null) {
            sender.sendMessage("§c找不到 Brain 配置: §e" + brainId);
            sender.sendMessage("§7可用的 Brain: §e"
                + config.getAllBrains().stream()
                    .map(BrainConfig::id)
                    .collect(Collectors.joining(", ")));
            return;
        }

        // 情绪档位检查
        EmotionLevel level;
        try {
            level = EmotionLevel.valueOf(emotionStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§c无效的情绪档位: §e" + emotionStr);
            sender.sendMessage("§7可用档位: " + Arrays.stream(EmotionLevel.values())
                .map(em -> em.name() + "(" + em.displayName + ")")
                .collect(Collectors.joining(", ")));
            return;
        }

        // 执行设置
        EmotionLevel oldLevel = emotionManager.getEmotion(targetPlayer.getUniqueId(), brain);
        emotionManager.setEmotion(targetPlayer.getUniqueId(), brain, level);

        sender.sendMessage("§a[AgenticNPC] §f已将 " + playerName + " 对 " + brain.name()
            + " 的情绪从 §e" + oldLevel.displayName + " §f设为 §a" + level.displayName);

        // 审计日志
        if (auditLogger != null) {
            auditLogger.logDialogueSuccess(
                targetPlayer.getUniqueId(), playerName, brainId,
                "ADMIN_SET_EMOTION: " + oldLevel.name() + " -> " + level.name(),
                null, ActionType.NONE, null
            );
        }
    }
}
