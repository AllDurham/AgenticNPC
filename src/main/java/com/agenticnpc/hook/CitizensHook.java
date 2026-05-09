package com.agenticnpc.hook;

import com.agenticnpc.storage.EntityBrainStorage;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.logging.Logger;

/**
 * Citizens NPC 交互 Hook。
 * 仅在服务器安装了 Citizens 时由 HookRegistry 注册。
 */
public class CitizensHook implements Listener {

    private final EntityBrainStorage brainStorage;
    private final ChatCollector      chatCollector;
    private final Logger             logger;

    public CitizensHook(EntityBrainStorage brainStorage,
                        ChatCollector chatCollector,
                        Logger logger) {
        this.brainStorage  = brainStorage;
        this.chatCollector = chatCollector;
        this.logger        = logger;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onNPCRightClick(NPCRightClickEvent event) {
        Player player = event.getClicker();

        // Citizens NPC 的实体可能没有 PersistentData 存储，
        // 所以绑定时需要使用 NPC 的 ID 作为 brainId 查询方式。
        // 这里我们检查 NPC 实体本身是否绑定了 brain。
        var npcEntity = event.getNPC().getEntity();
        if (npcEntity == null) return;

        brainStorage.retrieve(npcEntity).ifPresent(brainId -> {
            chatCollector.startListening(player, npcEntity, brainId);
            logger.info(String.format("[CitizensHook] 交互触发 | 玩家: %s | NPC: %s | Brain: %s",
                player.getName(), event.getNPC().getName(), brainId));
        });
    }
}
