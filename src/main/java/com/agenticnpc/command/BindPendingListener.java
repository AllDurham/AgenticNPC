package com.agenticnpc.command;

import com.agenticnpc.storage.EntityBrainStorage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * 监听右键事件，完成 bind/unbind/status 的实体选择阶段。
 * 与 VanillaHook 的区别：此 Listener 优先级更高，处理完后移除 Metadata 标记。
 */
public class BindPendingListener implements Listener {

    private final EntityBrainStorage brainStorage;
    private final Plugin             plugin;

    public BindPendingListener(EntityBrainStorage brainStorage, Plugin plugin) {
        this.brainStorage = brainStorage;
        this.plugin       = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();

        // ---- 处理 bind ----
        if (player.hasMetadata("anpc_pending_bind")) {
            event.setCancelled(true);

            String brainId = getStringMeta(player, "anpc_pending_bind");
            player.removeMetadata("anpc_pending_bind", plugin);

            if (brainId == null) return;

            brainStorage.store(event.getRightClicked(), brainId);
            player.sendMessage(String.format(
                "§a[AgenticNPC] §f绑定成功！实体 §e%s §f→ Brain §e%s",
                event.getRightClicked().getType().name(), brainId
            ));
            return;
        }

        // ---- 处理 unbind ----
        if (player.hasMetadata("anpc_pending_unbind")) {
            event.setCancelled(true);
            player.removeMetadata("anpc_pending_unbind", plugin);

            brainStorage.retrieve(event.getRightClicked()).ifPresentOrElse(
                brainId -> {
                    brainStorage.remove(event.getRightClicked());
                    player.sendMessage("§a[AgenticNPC] §f解绑成功！原 Brain: §e" + brainId);
                },
                () -> player.sendMessage("§7[AgenticNPC] 该实体未绑定任何 Brain。")
            );
            return;
        }

        // ---- 处理 status ----
        if (player.hasMetadata("anpc_pending_status")) {
            event.setCancelled(true);
            player.removeMetadata("anpc_pending_status", plugin);

            brainStorage.retrieve(event.getRightClicked()).ifPresentOrElse(
                brainId -> player.sendMessage(
                    "§a[AgenticNPC] §f实体 §e"
                    + event.getRightClicked().getType().name()
                    + " §f绑定的 Brain: §e" + brainId
                ),
                () -> player.sendMessage("§7[AgenticNPC] 该实体未绑定任何 Brain。")
            );
        }
    }

    private String getStringMeta(Player player, String key) {
        List<MetadataValue> values = player.getMetadata(key);
        return values.stream()
            .filter(v -> plugin.equals(v.getOwningPlugin()))
            .findFirst()
            .map(MetadataValue::asString)
            .orElse(null);
    }
}
