package com.agenticnpc.storage;

import org.bukkit.entity.Entity;
import java.util.Optional;

/**
 * 实体与 Brain ID 的绑定存储接口。
 * 高版本（1.14+）使用 PersistentDataContainer。
 * 低版本（1.12-1.13）使用 Metadata API。
 */
public interface EntityBrainStorage {
    void             store(Entity entity, String brainId);
    Optional<String> retrieve(Entity entity);
    void             remove(Entity entity);
}
