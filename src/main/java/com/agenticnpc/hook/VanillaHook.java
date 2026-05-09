package com.agenticnpc.hook;

import com.agenticnpc.storage.EntityBrainStorage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * 原版实体交互 Hook（始终注册，作为兜底）。
 *
 * 监听 PlayerInteractEntityEvent：
 * - 玩家右键任意实体
 * - 检查实体是否绑定了 Brain
 * - 绑定则触发 ChatCollector 进入对话状态
 */
public class VanillaHook implements Listener {

    private final EntityBrainStorage brainStorage;
    private final ChatCollector      chatCollector;
    private final Logger             logger;

    public VanillaHook(EntityBrainStorage brainStorage,
                       ChatCollector chatCollector,
                       Logger logger) {
        this.brainStorage  = brainStorage;
        this.chatCollector = chatCollector;
        this.logger        = logger;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        // 过滤副手触发（防止事件被触发两次）
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();

        // 检查实体是否绑定了 Brain
        Optional<String> brainId = brainStorage.retrieve(event.getRightClicked());
        if (brainId.isEmpty()) return;

        // 阻止默认交互行为（如与村民打开交易界面）
        event.setCancelled(true);

        // 进入对话状态
        chatCollector.startListening(player, event.getRightClicked(), brainId.get());

        logger.info(String.format("[VanillaHook] 交互触发 | 玩家: %s | 实体: %s | Brain: %s",
            player.getName(),
            event.getRightClicked().getType().name(),
            brainId.get()
        ));
    }
}
