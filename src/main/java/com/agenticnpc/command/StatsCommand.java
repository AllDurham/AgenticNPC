package com.agenticnpc.command;

import com.agenticnpc.audit.TokenTracker;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Calendar;
import java.util.UUID;

/**
 * /anpc stats 命令：查询 Token 消耗统计。
 */
public class StatsCommand {

    private final TokenTracker tokenTracker;

    public StatsCommand(TokenTracker tokenTracker) {
        this.tokenTracker = tokenTracker;
    }

    public void handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("agenticnpc.admin")) {
            sender.sendMessage("§c你没有权限使用此命令。");
            return;
        }

        long todayStart = getTodayStartMs();

        if (args.length == 1) {
            // /anpc stats → 全局统计
            var result = tokenTracker.queryGlobal(todayStart);
            sender.sendMessage("§e========= Token 统计（今日）=========");
            sender.sendMessage("§f总请求数:  §a" + result.requestCount());
            sender.sendMessage("§f总 Token:  §a" + result.totalTokens());
            sender.sendMessage("§f  Prompt:     §7" + result.promptTokens());
            sender.sendMessage("§f  Completion: §7" + result.completionTokens());
            sender.sendMessage("§e=====================================");
            return;
        }

        if (args.length >= 3 && "player".equalsIgnoreCase(args[1])) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage("§c找不到在线玩家: " + args[2]);
                return;
            }
            var result = tokenTracker.queryByPlayer(target.getUniqueId(), todayStart);
            sender.sendMessage(String.format(
                "§e%s 今日 Token 消耗: §a%d §7(请求 %d 次)",
                args[2], result.totalTokens(), result.requestCount()
            ));
            return;
        }

        if (args.length >= 3 && "brain".equalsIgnoreCase(args[1])) {
            var result = tokenTracker.queryByBrain(args[2], todayStart);
            sender.sendMessage(String.format(
                "§eBrain [%s] 今日 Token 消耗: §a%d §7(请求 %d 次)",
                args[2], result.totalTokens(), result.requestCount()
            ));
            return;
        }

        sender.sendMessage("§7用法: /anpc stats | stats player <名字> | stats brain <id>");
    }

    private long getTodayStartMs() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
}
