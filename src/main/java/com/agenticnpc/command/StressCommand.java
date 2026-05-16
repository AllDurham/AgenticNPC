package com.agenticnpc.command;

import com.agenticnpc.console.MetricsCollector;
import com.agenticnpc.dispatch.AsyncDispatcher;
import com.agenticnpc.model.InteractionEvent;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * /anpc stress <count> — 模拟 interaction 压力测试。
 *
 * 不真实发送 chat，直接进入 dispatcher pipeline。
 * 使用 mock InteractionEvent。
 * 不触发真实 ActionExecutor（ExecutionCallback 为 null 时只发消息）。
 * 不污染 Memory（ExecutionStage 的 memory append 会降级处理）。
 *
 * 仅 OP，默认上限 200。
 */
public class StressCommand {

    private static final int MAX_COUNT = 200;

    private final AsyncDispatcher  dispatcher;
    private final MetricsCollector metricsCollector;
    private final Plugin           plugin;
    private final Logger           logger;

    public StressCommand(AsyncDispatcher dispatcher, MetricsCollector metricsCollector,
                         Plugin plugin, Logger logger) {
        this.dispatcher      = dispatcher;
        this.metricsCollector = metricsCollector;
        this.plugin          = plugin;
        this.logger          = logger;
    }

    public void handle(Player player, String[] args) {
        if (!player.hasPermission("agenticnpc.admin")) {
            player.sendMessage("§c你没有权限使用此命令。");
            return;
        }

        int count = 10; // 默认 10 次
        if (args.length >= 2) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage("§c无效的数字: " + args[1]);
                return;
            }
        }

        if (count > MAX_COUNT) {
            player.sendMessage("§c最大压测次数: " + MAX_COUNT + "（你输入了 " + count + "）");
            count = MAX_COUNT;
        }

        if (count <= 0) {
            player.sendMessage("§c次数必须大于 0");
            return;
        }

        player.sendMessage("§e[Stress] 开始压测 | 次数: " + count + " | 并发: 异步提交");
        logger.info(String.format("[Stress] 玩家 %s 发起压测 | 次数: %d", player.getName(), count));

        long startMs = System.currentTimeMillis();

        // 使用 default_npc brain（存在即可）
        String brainId = "default_npc";

        for (int i = 0; i < count; i++) {
            // 创建 mock InteractionEvent（不通过 ChatCollector，直接提交 pipeline）
            InteractionEvent event = InteractionEvent.createStress(
                player, player, // npcEntity 用 player 自身（不执行 action 时不影响）
                brainId,
                "[stress test #" + i + "] 你好"
            );
            dispatcher.submit(event);
        }

        long submitMs = System.currentTimeMillis() - startMs;
        player.sendMessage("§a[Stress] 提交完成 | 耗时: " + submitMs + "ms");
        player.sendMessage("§7[Stress] Pipeline 正在异步处理中，完成后可通过 /anpc health 查看指标");
    }
}
