package com.agenticnpc.guard;

import com.agenticnpc.config.ConfigManager;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 传送安全守卫。
 *
 * 检查目标坐标是否在 WorldGuard 允许/禁止的领地范围内。
 *
 * 配置项（config.yml → teleport-guard）：
 * - enabled: 是否启用 WorldGuard 检查
 * - mode: "whitelist"（只允许传送到白名单领地）或 "blacklist"（禁止传送到黑名单领地）
 * - regions: 领地名称列表
 *
 * 未安装 WorldGuard 时，此守卫自动降级为"放行"。
 */
public class TeleportGuard {

    private final ConfigManager config;
    private final Logger        logger;
    private final boolean       worldGuardPresent;

    public TeleportGuard(ConfigManager config, Logger logger) {
        this.config  = config;
        this.logger  = logger;
        this.worldGuardPresent = isWorldGuardPresent();
    }

    public record TeleportCheckResult(boolean allowed, String reason) {
        public static TeleportCheckResult allow()               { return new TeleportCheckResult(true, null); }
        public static TeleportCheckResult deny(String reason)   { return new TeleportCheckResult(false, reason); }
    }

    /**
     * 检查目标位置是否允许传送。
     */
    public TeleportCheckResult check(Location target) {
        if (!config.isTeleportGuardEnabled()) {
            return TeleportCheckResult.allow();
        }

        if (!worldGuardPresent) {
            logger.warning("[TeleportGuard] WorldGuard 未安装，跳过领地检查");
            return TeleportCheckResult.allow();
        }

        Set<String> configuredRegions = config.getTeleportGuardRegions();
        if (configuredRegions.isEmpty()) {
            return TeleportCheckResult.allow();
        }

        // 获取目标坐标所在的领地集合
        Set<String> actualRegions = getRegionsAt(target);
        String mode = config.getTeleportGuardMode();

        if ("whitelist".equalsIgnoreCase(mode)) {
            // 白名单模式：目标必须至少在一个白名单领地内
            boolean inAllowed = actualRegions.stream()
                .anyMatch(configuredRegions::contains);
            if (!inAllowed) {
                return TeleportCheckResult.deny(
                    "只能传送到指定区域（" + String.join(", ", configuredRegions) + "）"
                );
            }
        } else if ("blacklist".equalsIgnoreCase(mode)) {
            // 黑名单模式：目标不能在任何一个黑名单领地内
            boolean inBlocked = actualRegions.stream()
                .anyMatch(configuredRegions::contains);
            if (inBlocked) {
                return TeleportCheckResult.deny(
                    "不能传送到受保护区域（" + String.join(", ", configuredRegions) + "）"
                );
            }
        }

        return TeleportCheckResult.allow();
    }

    /**
     * 获取指定位置的所有 WorldGuard 领地名称。
     */
    private Set<String> getRegionsAt(Location location) {
        try {
            World world = location.getWorld();
            if (world == null) return Set.of();

            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionManager manager = container.get(BukkitAdapter.adapt(world));
            if (manager == null) return Set.of();

            ApplicableRegionSet regions = manager.getApplicableRegions(
                BukkitAdapter.asBlockVector(location)
            );

            return regions.getRegions().stream()
                .map(ProtectedRegion::getId)
                .collect(Collectors.toSet());

        } catch (Exception e) {
            logger.warning("[TeleportGuard] WorldGuard 查询异常: " + e.getMessage());
            return Set.of();
        }
    }

    private boolean isWorldGuardPresent() {
        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
