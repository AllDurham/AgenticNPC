package com.agenticnpc.config;

import com.agenticnpc.AgenticNPCPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;
import java.util.stream.Collectors;

public class ConfigManager {

    private final AgenticNPCPlugin plugin;
    private FileConfiguration config;
    private final Map<String, BrainConfig> brainConfigMap = new HashMap<>();

    public ConfigManager(AgenticNPCPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        loadBrainConfigs();
        plugin.getLogger().info("[配置] 配置加载完成，共加载 "
            + brainConfigMap.size() + " 个 Brain 配置");
    }

    private void loadBrainConfigs() {
        brainConfigMap.clear();
        List<Map<?, ?>> brainList = config.getMapList("brains");

        for (Map<?, ?> brainMap : brainList) {
            try {
                String id          = (String) brainMap.get("id");
                String name        = (String) brainMap.get("name");
                String personality = (String) brainMap.get("personality");
                String fallback    = (String) brainMap.get("fallback-dialogue");
                String sound       = brainMap.containsKey("dialogue-sound")
                                         ? (String) brainMap.get("dialogue-sound")
                                         : "ENTITY_VILLAGER_AMBIENT";
                double volume      = brainMap.containsKey("dialogue-sound-volume")
                                         ? toDouble(brainMap.get("dialogue-sound-volume")) : 1.0;
                double pitch       = brainMap.containsKey("dialogue-sound-pitch")
                                         ? toDouble(brainMap.get("dialogue-sound-pitch")) : 1.0;
                boolean actionBar  = brainMap.containsKey("action-bar-enabled")
                                         ? (boolean) brainMap.get("action-bar-enabled") : true;

                @SuppressWarnings("unchecked")
                List<String> actionTypes = brainMap.containsKey("allowed-action-types")
                                               ? (List<String>) brainMap.get("allowed-action-types")
                                               : List.of();

                @SuppressWarnings("unchecked")
                List<String> itemNames = brainMap.containsKey("allowed-items")
                                             ? (List<String>) brainMap.get("allowed-items")
                                             : List.of();

                @SuppressWarnings("unchecked")
                List<String> effectNames = brainMap.containsKey("allowed-potion-effects")
                                               ? (List<String>) brainMap.get("allowed-potion-effects")
                                               : List.of();

                // 将物品名解析为 Material Set
                Set<Material> allowedItems = itemNames.stream()
                    .map(itemName -> Material.matchMaterial(itemName.toUpperCase()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

                Set<String> allowedActionTypes = actionTypes.stream()
                    .map(String::toUpperCase)
                    .collect(Collectors.toSet());

                Set<String> allowedPotionEffects = effectNames.stream()
                    .map(String::toUpperCase)
                    .collect(Collectors.toSet());

                BrainConfig brainConfig = new BrainConfig(
                    id, name, personality, fallback,
                    allowedActionTypes, allowedItems, allowedPotionEffects,
                    sound, (float) volume, (float) pitch, actionBar
                );

                brainConfigMap.put(id, brainConfig);

            } catch (Exception e) {
                plugin.getLogger().warning("[配置] 解析 Brain 配置失败: " + e.getMessage());
            }
        }
    }

    // ---- LLM 配置 ----
    public String getLLMEndpoint()      { return config.getString("llm.endpoint"); }
    public String getLLMApiKey()        { return config.getString("llm.api-key"); }
    public String getLLMModel()         { return config.getString("llm.model", "gpt-4o-mini"); }
    public double getLLMTemperature()   { return config.getDouble("llm.temperature", 0.8); }
    public int    getLLMMaxTokens()     { return config.getInt("llm.max-tokens", 256); }
    public int    getConnectTimeoutMs() { return config.getInt("llm.timeout.connect-ms", 3000); }
    public int    getReadTimeoutMs()    { return config.getInt("llm.timeout.read-ms", 8000); }

    // ---- 限流配置 ----
    public boolean isGlobalRateLimit()    { return config.getBoolean("rate-limit.global-per-player", false); }
    public int     getRateLimitMax()      { return config.getInt("rate-limit.max-requests", 5); }
    public long    getRateLimitPeriodMs() { return config.getLong("rate-limit.period-ms", 60000); }

    // ---- 熔断器配置 ----
    public int  getCircuitBreakerThreshold()  { return config.getInt("circuit-breaker.failure-threshold", 5); }
    public long getCircuitBreakerRecoveryMs() { return config.getLong("circuit-breaker.recovery-time-ms", 30000); }

    // ---- 记忆配置 ----
    public long getMemoryTtlMinutes()     { return config.getLong("memory.ttl-minutes", 5); }
    public long getMemoryMaxSessions()    { return config.getLong("memory.max-sessions", 500); }
    public int  getMaxHistoryPerSession() { return config.getInt("memory.max-history-per-session", 10); }

    // ---- 其他 ----
    public boolean isInventoryContextEnabled() { return config.getBoolean("context.inventory-enabled", true); }
    public boolean isActionBarEnabled()        { return config.getBoolean("render.action-bar-enabled", true); }
    public boolean isSoundEnabled()            { return config.getBoolean("render.sound-enabled", true); }
    public String  getFallbackDialogue()       { return config.getString("fallback.dialogue", "..."); }
    public boolean isDebugMode()               { return config.getBoolean("debug", false); }

    // ---- 数据库配置 ----
    public String getDbBackend()     { return config.getString("database.backend", "sqlite"); }
    public String getSqliteFile()    { return config.getString("database.sqlite.file", "memories.db"); }
    public String getMysqlHost()     { return config.getString("database.mysql.host", "localhost"); }
    public int    getMysqlPort()     { return config.getInt("database.mysql.port", 3306); }
    public String getMysqlDatabase() { return config.getString("database.mysql.database"); }
    public String getMysqlUser()     { return config.getString("database.mysql.username"); }
    public String getMysqlPassword() { return config.getString("database.mysql.password"); }

    // ---- 传送守卫配置（WorldGuard）----
    public boolean isTeleportGuardEnabled() {
        return config.getBoolean("teleport-guard.enabled", false);
    }
    public String getTeleportGuardMode() {
        return config.getString("teleport-guard.mode", "blacklist");
    }
    public Set<String> getTeleportGuardRegions() {
        return new HashSet<>(config.getStringList("teleport-guard.regions"));
    }

    // ---- 药水效果白名单 ----
    public Set<String> getAllowedPotionEffects(String brainId) {
        return getBrainConfig(brainId)
            .map(BrainConfig::allowedPotionEffects)
            .orElse(Set.of());
    }

    // ---- 服务器标识 ----
    public String getServerId() {
        return config.getString("server-id", "default");
    }

    // ---- Token 告警 ----
    public int getTokenAlertThreshold() {
        return config.getInt("token-alert.single-request-threshold", 0);
    }

    public int getDailyPlayerTokenThreshold() {
        return config.getInt("token-alert.daily-player-threshold", 0);
    }

    // ---- 审计日志 ----
    public boolean isAuditEnabled() {
        return config.getBoolean("audit.enabled", true);
    }

    public int getAuditRetentionDays() {
        return config.getInt("audit.retention-days", 30);
    }

    // ---- BungeeCord 同步 ----
    public int getBungeecordSyncIntervalSeconds() {
        return config.getInt("rate-limit.bungeecord-sync-interval-seconds", 30);
    }

    // ---- Brain 配置 ----
    public Optional<BrainConfig> getBrainConfig(String brainId) {
        return Optional.ofNullable(brainConfigMap.get(brainId));
    }

    public Set<Material> getAllowedItems(String brainId) {
        return getBrainConfig(brainId)
            .map(BrainConfig::allowedItems)
            .orElse(Set.of());
    }

    public boolean isSoundEnabled(String brainId) {
        if (!isSoundEnabled()) return false;
        return getBrainConfig(brainId)
            .map(b -> b.dialogueSound() != null)
            .orElse(false);
    }

    public Collection<BrainConfig> getAllBrains() {
        return Collections.unmodifiableCollection(brainConfigMap.values());
    }

    private double toDouble(Object obj) {
        if (obj instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(obj.toString()); }
        catch (Exception e) { return 1.0; }
    }
}
