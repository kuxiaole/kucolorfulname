package cc.chlchun.kucolorfulname.hook;

import cc.chlchun.kucolorfulname.api.PlaceholderSnapshot;
import cc.chlchun.kucolorfulname.platform.OnlinePlayerIndex;
import cc.chlchun.kucolorfulname.service.ColorfulNameService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class KuColorfulNameExpansion extends PlaceholderExpansion {
    private final Plugin plugin;
    private final ColorfulNameService service;
    private final OnlinePlayerIndex onlinePlayers;

    public KuColorfulNameExpansion(
            Plugin plugin,
            ColorfulNameService service,
            OnlinePlayerIndex onlinePlayers
    ) {
        this.plugin = plugin;
        this.service = service;
        this.onlinePlayers = onlinePlayers;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "kucolorfulname";
    }

    @Override
    public @NotNull String getAuthor() {
        return "xiaole";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String parameters) {
        if (player == null) {
            return "";
        }
        UUID playerId = player.getUniqueId();
        Optional<PlaceholderSnapshot> cached = service.cachedPlaceholderSnapshot(playerId);
        String fallbackName = cached.map(PlaceholderSnapshot::playerName)
                .or(() -> onlinePlayers.entry(playerId).map(OnlinePlayerIndex.Entry::name))
                .orElseGet(playerId::toString);
        String normalized = parameters.trim().toLowerCase(Locale.ROOT);
        Component renderedName = normalized.equals("name") || normalized.equals("name_mm")
                ? service.renderActive(playerId, fallbackName) : null;
        return PlaceholderResolver.resolve(
                cached,
                fallbackName,
                parameters,
                Instant.now(),
                renderedName
        );
    }
}
