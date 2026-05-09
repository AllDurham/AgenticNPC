package com.agenticnpc.storage;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * 基于 PersistentDataContainer 的绑定存储（1.14+）。
 * 数据随实体持久化，服务器重启不丢失。
 */
public class PersistentDataStorage implements EntityBrainStorage {

    private final NamespacedKey key;

    public PersistentDataStorage(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "brain_id");
    }

    @Override
    public void store(Entity entity, String brainId) {
        entity.getPersistentDataContainer()
              .set(key, PersistentDataType.STRING, brainId);
    }

    @Override
    public Optional<String> retrieve(Entity entity) {
        return Optional.ofNullable(
            entity.getPersistentDataContainer()
                  .get(key, PersistentDataType.STRING)
        );
    }

    @Override
    public void remove(Entity entity) {
        entity.getPersistentDataContainer().remove(key);
    }
}
