package com.agenticnpc.command;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.emotion.EmotionLevel;
import com.agenticnpc.storage.EntityBrainStorage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 绑定/解绑命令。
 *
 * 用法：
 *   /anpc bind <brainId>   — 右键后下一个实体将被绑定
 *   /anpc unbind           — 右键后解绑实体
 *   /anpc status           — 查看当前手持实体的绑定状态
 *   /anpc reload           — 重载配置
 *
 * 权限节点：agenticnpc.admin
 */
public class BindCommand implements CommandExecutor, TabCompleter {

    private final EntityBrainStorage brainStorage;
    private final ConfigManager      config;
    private StatsCommand             statsCommand;
    private EmotionCommand           emotionCommand;
    private HealthCommand            healthCommand;
    private StressCommand            stressCommand;

    public BindCommand(EntityBrainStorage brainStorage, ConfigManager config) {
        this.brainStorage = brainStorage;
        this.config       = config;
    }

    public void setStatsCommand(StatsCommand statsCommand) {
        this.statsCommand = statsCommand;
    }

    public void setEmotionCommand(EmotionCommand emotionCommand) {
        this.emotionCommand = emotionCommand;
    }

    public void setHealthCommand(HealthCommand healthCommand) {
        this.healthCommand = healthCommand;
    }

    public void setStressCommand(StressCommand stressCommand) {
        this.stressCommand = stressCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令只能由玩家执行。");
            return true;
        }

        if (!player.hasPermission("agenticnpc.admin")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "bind"    -> handleBind(player, args);
            case "unbind"  -> handleUnbind(player);
            case "status"  -> handleStatus(player);
            case "reload"  -> handleReload(player);
            case "stats"   -> { if (statsCommand != null) statsCommand.handle(player, args); yield true; }
            case "emotion" -> { if (emotionCommand != null) emotionCommand.handle(player, args); yield true; }
            case "health"  -> { if (healthCommand != null) healthCommand.handle(player, args); yield true; }
            case "stress"  -> { if (stressCommand != null) stressCommand.handle(player, args); yield true; }
            default        -> { sendHelp(player); yield true; }
        };
    }

    /**
     * bind 命令：让玩家右键一个实体来完成绑定。
     * 使用临时 Metadata 标记"待绑定"状态，由 BindPendingListener 完成后续。
     */
    private boolean handleBind(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§c用法: /anpc bind <brainId>");
            return true;
        }

        String brainId = args[1];

        // 校验 Brain ID 是否存在
        if (config.getBrainConfig(brainId).isEmpty()) {
            player.sendMessage("§c找不到 Brain 配置: §e" + brainId);
            player.sendMessage("§7可用的 Brain: §e"
                + config.getAllBrains().stream()
                    .map(b -> b.id())
                    .collect(Collectors.joining(", ")));
            return true;
        }

        // 将待绑定信息存入玩家 Metadata，等待下一次右键实体
        player.setMetadata("anpc_pending_bind",
            new org.bukkit.metadata.FixedMetadataValue(
                org.bukkit.Bukkit.getPluginManager().getPlugin("AgenticNPC"),
                brainId
            )
        );

        player.sendMessage("§a[AgenticNPC] §f请右键点击你想绑定的实体...");
        player.sendMessage("§7将绑定 Brain: §e" + brainId);
        return true;
    }

    private boolean handleUnbind(Player player) {
        player.setMetadata("anpc_pending_unbind",
            new org.bukkit.metadata.FixedMetadataValue(
                org.bukkit.Bukkit.getPluginManager().getPlugin("AgenticNPC"),
                true
            )
        );
        player.sendMessage("§a[AgenticNPC] §f请右键点击你想解绑的实体...");
        return true;
    }

    private boolean handleStatus(Player player) {
        player.sendMessage("§a[AgenticNPC] §f请右键点击实体以查看绑定状态...");
        player.setMetadata("anpc_pending_status",
            new org.bukkit.metadata.FixedMetadataValue(
                org.bukkit.Bukkit.getPluginManager().getPlugin("AgenticNPC"),
                true
            )
        );
        return true;
    }

    private boolean handleReload(Player player) {
        config.reload();

        var plugin = com.agenticnpc.AgenticNPCPlugin.getInstance();
        plugin.rebuildLLMClient();
        plugin.getAsyncDispatcher().getCircuitBreaker().reset();
        plugin.getProfileManager().invalidateCache();

        player.sendMessage("§a[AgenticNPC] §f配置已热重载！");
        player.sendMessage("§7- API Key 已更新");
        player.sendMessage("§7- 熔断器已重置");
        player.sendMessage("§7- 画像缓存已清空");
        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage("§e========= AgenticNPC 命令 =========");
        player.sendMessage("§f/anpc bind <brainId>               §7- 绑定 Brain 到实体");
        player.sendMessage("§f/anpc unbind                       §7- 解绑实体");
        player.sendMessage("§f/anpc status                       §7- 查看绑定状态");
        player.sendMessage("§f/anpc reload                       §7- 重载配置");
        player.sendMessage("§f/anpc stats [player|brain] [id]    §7- Token 统计");
        player.sendMessage("§f/anpc emotion <player> <brain> <lv>§7- 设置情绪档位");
        player.sendMessage("§f/anpc profile <set|get|delete> ... §7- 管理画像");
        player.sendMessage("§e===================================");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                       String alias, String[] args) {
        if (args.length == 1) {
            return List.of("bind", "unbind", "status", "reload", "stats", "emotion", "profile", "health", "stress").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
        }

        // ---- bind 补全 ----
        if (args.length == 2 && "bind".equalsIgnoreCase(args[0])) {
            return config.getAllBrains().stream()
                .map(b -> b.id())
                .filter(id -> id.startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }

        // ---- stats 补全 ----
        if (args.length == 2 && "stats".equalsIgnoreCase(args[0])) {
            return List.of("player", "brain").stream()
                .filter(s -> s.startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }
        if (args.length == 3 && "stats".equalsIgnoreCase(args[0])) {
            if ("player".equalsIgnoreCase(args[1])) {
                return onlinePlayerNames(args[2]);
            }
            if ("brain".equalsIgnoreCase(args[1])) {
                return brainIdList(args[2]);
            }
        }

        // ---- emotion 补全 ----
        // /anpc emotion <player> <brainId> <emotionLevel>
        if ("emotion".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return onlinePlayerNames(args[1]);
            }
            if (args.length == 3) {
                return brainIdList(args[2]);
            }
            if (args.length == 4) {
                return Arrays.stream(EmotionLevel.values())
                    .map(Enum::name)
                    .filter(n -> n.startsWith(args[3].toUpperCase()))
                    .collect(Collectors.toList());
            }
        }

        // ---- profile 补全 ----
        // /anpc profile <set|get|delete> [player] [brainId]
        if ("profile".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return List.of("set", "get", "delete").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
            }
            if (args.length == 3) {
                return onlinePlayerNames(args[2]);
            }
            if (args.length == 4) {
                return brainIdList(args[3]);
            }
        }

        return List.of();
    }

    // ---- Tab 补全工具方法 ----
    private List<String> onlinePlayerNames(String prefix) {
        return org.bukkit.Bukkit.getOnlinePlayers().stream()
            .map(org.bukkit.entity.Player::getName)
            .filter(n -> n.toLowerCase().startsWith(prefix.toLowerCase()))
            .collect(Collectors.toList());
    }

    private List<String> brainIdList(String prefix) {
        return config.getAllBrains().stream()
            .map(b -> b.id())
            .filter(id -> id.startsWith(prefix.toLowerCase()))
            .collect(Collectors.toList());
    }
}
