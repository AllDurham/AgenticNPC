package com.agenticnpc.gateway;

import com.agenticnpc.config.ConfigManager;
import com.agenticnpc.gateway.model.ItemSafetyResult;
import com.agenticnpc.model.ActionParameters;
import org.bukkit.Material;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 物品给予安全守卫。
 *
 * 防护目标：
 * 1. 非法/不存在的 Material 名称 -> 崩服风险
 * 2. 超出 Brain 白名单的物品 -> 越权风险
 * 3. 数量越界（负数、超大值）-> 恶意复制风险
 * 4. 特殊 NBT 注入 -> 通过纯净 ItemStack 构造从架构上消除
 *
 * 跨版本兼容：Prompt 约束 LLM 输出 1.12 规范名 + 别名映射表双保险。
 */
public class ItemSafetyGuard {

    private static final int MAX_AMOUNT = 64;
    private static final int MIN_AMOUNT = 1;

    /**
     * 跨版本 Material 别名映射表。
     *
     * Key:   LLM 可能输出的名称（旧版或新版）
     * Value: 当前运行版本可能识别的备选名称
     *
     * 策略：先用 matchMaterial 直接匹配，失败后查此表。
     * Prompt 已约束 LLM 输出 1.12 规范名，此表作为兜底。
     */
    private static final Map<String, String> ALIAS_MAP = Map.ofEntries(
        Map.entry("CRAFTING_TABLE",  "WORKBENCH"),
        Map.entry("WORKBENCH",       "CRAFTING_TABLE"),
        Map.entry("GRASS_BLOCK",     "GRASS"),
        Map.entry("GRASS",           "GRASS_BLOCK"),
        Map.entry("OAK_SIGN",        "SIGN"),
        Map.entry("SIGN",            "OAK_SIGN"),
        Map.entry("OAK_LOG",         "LOG"),
        Map.entry("LOG",             "OAK_LOG"),
        Map.entry("OAK_PLANKS",      "WOOD"),
        Map.entry("WOOD",            "OAK_PLANKS"),
        Map.entry("IRON_SHOVEL",     "IRON_SPADE"),
        Map.entry("IRON_SPADE",      "IRON_SHOVEL"),
        Map.entry("GOLDEN_SWORD",    "GOLD_SWORD"),
        Map.entry("GOLD_SWORD",      "GOLDEN_SWORD"),
        Map.entry("GOLDEN_INGOT",    "GOLD_INGOT"),
        Map.entry("GUNPOWDER",       "SULPHUR"),
        Map.entry("SULPHUR",         "GUNPOWDER"),
        Map.entry("COD",             "RAW_FISH"),
        Map.entry("RAW_FISH",        "COD"),
        Map.entry("ROSE_RED",        "RED_DYE"),
        Map.entry("RED_DYE",         "ROSE_RED")
    );

    private final ConfigManager config;
    private final Logger        logger;

    public ItemSafetyGuard(ConfigManager config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /**
     * 执行物品安全检查。
     *
     * @param params  LLM 返回的动作参数
     * @param brainId 当前 NPC 的 Brain ID
     * @return 安全检查结果
     */
    public ItemSafetyResult check(ActionParameters params, String brainId) {
        // 1. 参数非空校验
        if (params == null || params.item_id() == null || params.item_id().isBlank()) {
            return ItemSafetyResult.unsafe("item_id 为空");
        }

        // 2. Material 解析（含跨版本兼容回退）
        Optional<Material> materialOpt = resolveMaterial(params.item_id());
        if (materialOpt.isEmpty()) {
            logger.warning(String.format(
                "[安全][ItemSafetyGuard] 无法解析 Material | item_id: %s | BrainId: %s",
                params.item_id(), brainId
            ));
            return ItemSafetyResult.unsafe("无效的物品 ID: " + params.item_id());
        }

        Material material = materialOpt.get();

        // 3. 排除特殊材料
        if (material == Material.AIR || material.name().startsWith("LEGACY_")) {
            return ItemSafetyResult.unsafe("不允许给予的材料类型: " + material.name());
        }

        // 4. Brain 白名单校验
        Set<Material> allowedItems = config.getAllowedItems(brainId);
        if (!allowedItems.contains(material)) {
            logger.warning(String.format(
                "[安全][ItemSafetyGuard] 越权物品被拦截 | Material: %s | BrainId: %s | 白名单: %s",
                material.name(), brainId, allowedItems
            ));
            return ItemSafetyResult.unsafe("该 NPC 无权给予: " + material.name());
        }

        // 5. 数量安全截断（硬性边界，不抛异常）
        int safeAmount = Math.min(Math.max(params.amount(), MIN_AMOUNT), MAX_AMOUNT);

        if (config.isDebugMode()) {
            logger.info(String.format(
                "[Debug][ItemSafetyGuard] 物品校验通过 | Material: %s | 数量: %d | BrainId: %s",
                material.name(), safeAmount, brainId
            ));
        }

        return ItemSafetyResult.safe(material, safeAmount);
    }

    /**
     * 带版本兼容回退的 Material 解析。
     *
     * Step 1: matchMaterial 直接匹配（正常路径）
     * Step 2: 别名映射表回退
     * Step 3: 全部失败，返回 empty
     */
    private Optional<Material> resolveMaterial(String itemId) {
        String normalized = itemId.toUpperCase().trim();

        // Step 1: 直接匹配
        Material direct = Material.matchMaterial(normalized);
        if (direct != null) return Optional.of(direct);

        // Step 2: 别名映射回退
        String alias = ALIAS_MAP.get(normalized);
        if (alias != null) {
            Material aliased = Material.matchMaterial(alias);
            if (aliased != null) {
                logger.info(String.format(
                    "[兼容][ItemSafetyGuard] Material 别名映射触发: %s -> %s",
                    normalized, alias
                ));
                return Optional.of(aliased);
            }
        }

        return Optional.empty();
    }
}
