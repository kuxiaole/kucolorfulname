package cc.chlchun.kucolorfulname.listener;

import cc.chlchun.kucolorfulname.platform.DisplayManager;
import cc.chlchun.kucolorfulname.platform.AnimationManager;
import cc.chlchun.kucolorfulname.platform.OnlinePlayerIndex;
import cc.chlchun.kucolorfulname.platform.PlayerTasks;
import cc.chlchun.kucolorfulname.service.ColorfulNameService;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerLifecycleListener implements Listener {
    private final ColorfulNameService service;
    private final DisplayManager display;
    private final AnimationManager animations;
    private final PlayerTasks tasks;
    private final OnlinePlayerIndex onlinePlayers;
    private final Logger logger;
    private final BooleanSupplier pluginEnabled;

    public PlayerLifecycleListener(
            ColorfulNameService service,
            DisplayManager display,
            AnimationManager animations,
            PlayerTasks tasks,
            OnlinePlayerIndex onlinePlayers,
            Logger logger,
            BooleanSupplier pluginEnabled
    ) {
        this.service = service;
        this.display = display;
        this.animations = animations;
        this.tasks = tasks;
        this.onlinePlayers = onlinePlayers;
        this.logger = logger;
        this.pluginEnabled = pluginEnabled;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String playerName = event.getPlayer().getName();
        onlinePlayers.add(playerId, playerName);
        service.load(playerId).whenComplete((ignored, error) -> {
            if (!pluginEnabled.getAsBoolean()) {
                return;
            }
            if (error != null) {
                logger.log(Level.SEVERE, "Failed to load colorful name for " + playerId, error);
                return;
            }
            tasks.scheduler().runForOnlinePlayer(playerId, current -> {
                if (service.isCached(playerId)) {
                    display.refresh(current);
                }
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        animations.untrack(playerId);
        onlinePlayers.remove(playerId);
        service.unload(playerId);
    }
}
