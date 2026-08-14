package cc.chlchun.kucolorfulname.platform;

import cc.chlchun.kucolorfulname.api.PlaceholderSnapshot;
import cc.chlchun.kucolorfulname.config.ColorfulNameConfig;
import cc.chlchun.kucolorfulname.service.ColorfulNameService;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public final class DisplayManager {
    private final ColorfulNameService service;
    private final Supplier<ColorfulNameConfig> config;
    private final PlayerTasks tasks;
    private final AnimationManager animations;
    private final Set<UUID> pendingTabResets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public DisplayManager(
            ColorfulNameService service,
            Supplier<ColorfulNameConfig> config,
            PlayerTasks tasks,
            AnimationManager animations
    ) {
        this.service = service;
        this.config = config;
        this.tasks = tasks;
        this.animations = animations;
    }

    public void refresh(Player player) {
        if (closed.get()) {
            return;
        }
        service.ensureDefaultStyle(player).whenComplete((ignored, error) -> {
            if (error != null || closed.get()) {
                return;
            }
            tasks.submit(player, () -> {
                    if (!closed.get()) {
                        apply(player);
                    }
                });
        });
    }

    private void apply(Player player) {
        if (closed.get()) {
            return;
        }
        PlaceholderSnapshot snapshot = service.refreshPlaceholderSnapshot(player);
        boolean applyToTabList = config.get().settings().applyToTabList();
        applyNameIfChanged(player, snapshot.renderedName(), applyToTabList);
        boolean resetPlainTabName = pendingTabResets.remove(player.getUniqueId());
        if (!applyToTabList && resetPlainTabName) {
            resetTabNameIfChanged(player);
        }
        animations.update(player, snapshot);
        animations.track(player);
    }

    public void reset(Player player) {
        if (closed.get()) {
            return;
        }
        tasks.run(player, () -> {
            if (closed.get()) {
                return;
            }
            PlaceholderSnapshot snapshot = service.refreshPlaceholderSnapshot(player);
            boolean applyToTabList = config.get().settings().applyToTabList();
            applyNameIfChanged(player, Component.text(player.getName()), applyToTabList);
            boolean resetPlainTabName = pendingTabResets.remove(player.getUniqueId());
            if (!applyToTabList && resetPlainTabName) {
                resetTabNameIfChanged(player);
            }
            animations.update(player, snapshot);
            animations.track(player);
        });
    }

    public void resetTabNamesOnNextRefresh(Collection<UUID> playerIds) {
        if (!closed.get()) {
            pendingTabResets.addAll(playerIds);
        }
    }

    public void close() {
        closed.set(true);
        pendingTabResets.clear();
    }

    static void applyNameIfChanged(Player player, Component name, boolean applyToTabList) {
        if (!name.equals(player.displayName())) {
            player.displayName(name);
        }
        if (applyToTabList && !name.equals(player.playerListName())) {
            player.playerListName(name);
        }
    }

    static void resetTabNameIfChanged(Player player) {
        Component plainName = Component.text(player.getName());
        if (!plainName.equals(player.playerListName())) {
            player.playerListName(plainName);
        }
    }
}
