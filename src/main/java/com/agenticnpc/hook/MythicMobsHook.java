package com.agenticnpc.hook;

import com.agenticnpc.storage.EntityBrainStorage;
import io.lumine.mythic.bukkit.events.MythicMobInteractEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.logging.Logger;

/**
 * MythicMobs 交互 Hook。
 * 仅在服务器安装了 MythicMobs 时由 HookRegistry 注册。
 */
public class MythicMobsHook implements Listener {

    private final EntityBrainStorage brainStorage;
    private final ChatCollector      chatCollector;
    private final Logger             logger;

    public MythicMobsHook(EntityBrainStorage brainStorage,
                          ChatCollector chatCollector,
                          Logger logger) {
        this.brainStorage  = brainStorage;
        this.chatCollector = chatCollector;
        this.logger        = logger;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMythicMobInteract(MythicMobInteractEvent event) {
        Player player = event.getPlayer();

        // ActiveMob → AbstractEntity → Bukkit Entity
        Entity entity = event.getActiveMob().getEntity().getBukkitEntity();

        brainStorage.retrieve(entity).ifPresent(brainId -> {
            chatCollector.startListening(player, entity, brainId);
            logger.info(String.format("[MythicMobsHook] 交互触发 | 玩家: %s | 怪物: %s | Brain: %s",
                player.getName(), event.getActiveMobType().getInternalName(), brainId));
        });
    }
}
