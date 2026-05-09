package com.agenticnpc.storage;

import org.bukkit.entity.Entity;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;

/**
 * 基于 Metadata API 的绑定存储（1.12-1.13 兼容）。
 * 注意：Metadata 重启后丢失，需配合 data.yml 持久化。
 * 由 EntityBrainStorageFactory 在运行时自动选择。
 */
public class MetadataStorage implements EntityBrainStorage {

    private static final String METADATA_KEY = "npc_brain_id";
    private final Plugin plugin;

    public MetadataStorage(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void store(Entity entity, String brainId) {
        entity.setMetadata(METADATA_KEY,
            new FixedMetadataValue(plugin, brainId));
    }

    @Override
    public Optional<String> retrieve(Entity entity) {
        List<MetadataValue> values = entity.getMetadata(METADATA_KEY);
        return values.stream()
            .filter(v -> plugin.equals(v.getOwningPlugin()))
            .findFirst()
            .map(MetadataValue::asString);
    }

    @Override
    public void remove(Entity entity) {
        entity.removeMetadata(METADATA_KEY, plugin);
    }
}
