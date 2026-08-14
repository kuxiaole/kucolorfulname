package cc.chlchun.kucolorfulname.config;

import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(
        boolean applyToTabList,
        int expiryCheckSeconds,
        int animationRefreshTicks,
        boolean nmsTabBatching,
        String repositoryTitle,
        String adminRepositoryTitle
) {
    public static PluginSettings load(FileConfiguration config) {
        return new PluginSettings(
                config.getBoolean("settings.apply-to-tab-list", true),
                Math.max(5, config.getInt("settings.expiry-check-seconds", 30)),
                Math.max(1, Math.min(20,
                        config.getInt("settings.animation.refresh-ticks", 1))),
                config.getBoolean("settings.animation.nms-tab-batching", true),
                config.getString("settings.repository-title", "颜色昵称仓库"),
                config.getString("settings.admin-repository-title", "管理颜色昵称：%player%")
        );
    }
}
