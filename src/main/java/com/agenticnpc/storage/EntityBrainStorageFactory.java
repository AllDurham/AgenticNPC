package com.agenticnpc.storage;

import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * 运行时检测服务端 API 版本，选择合适的存储实现。
 * 单例模式，整个插件生命周期内只初始化一次。
 */
public class EntityBrainStorageFactory {

    private static EntityBrainStorage instance;

    public static synchronized EntityBrainStorage getInstance(Plugin plugin, Logger logger) {
        if (instance == null) {
            instance = create(plugin, logger);
        }
        return instance;
    }

    /** 仅在 onDisable 时调用，清理单例以便热重载 */
    public static synchronized void reset() {
        instance = null;
    }

    private static EntityBrainStorage create(Plugin plugin, Logger logger) {
        try {
            Class.forName("org.bukkit.persistence.PersistentDataContainer");
            logger.info("[存储] 使用 PersistentDataContainer（1.14+ 模式）");
            return new PersistentDataStorage(plugin);
        } catch (ClassNotFoundException e) {
            logger.info("[存储] 使用 Metadata API（1.12-1.13 兼容模式）");
            return new MetadataStorage(plugin);
        }
    }
}
