package com.agenticnpc.hook;

import com.agenticnpc.storage.EntityBrainStorage;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Hook 注册中心。
 * 系统启动时执行类加载探测，动态注册对应的 Listener。
 */
public class HookRegistry {

    private final Plugin             plugin;
    private final EntityBrainStorage brainStorage;
    private final ChatCollector      chatCollector;
    private final Logger             logger;

    public HookRegistry(Plugin plugin,
                        EntityBrainStorage brainStorage,
                        ChatCollector chatCollector,
                        Logger logger) {
        this.plugin        = plugin;
        this.brainStorage  = brainStorage;
        this.chatCollector = chatCollector;
        this.logger        = logger;
    }

    public void registerAll() {
        // Vanilla Hook（始终注册）
        register(new VanillaHook(brainStorage, chatCollector, logger));
        logger.info("[Hook] Vanilla Hook 已注册");

        // Citizens Hook（探测后注册）
        if (isClassPresent("net.citizensnpcs.api.event.NPCRightClickEvent")) {
            register(new CitizensHook(brainStorage, chatCollector, logger));
            logger.info("[Hook] Citizens Hook 已注册");
        }

        // MythicMobs Hook（探测后注册）
        if (isClassPresent("io.lumine.mythic.bukkit.events.MythicMobInteractEvent")) {
            register(new MythicMobsHook(brainStorage, chatCollector, logger));
            logger.info("[Hook] MythicMobs Hook 已注册");
        }
    }

    private void register(org.bukkit.event.Listener listener) {
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
