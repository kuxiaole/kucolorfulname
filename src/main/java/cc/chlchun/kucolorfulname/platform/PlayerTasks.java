package cc.chlchun.kucolorfulname.platform;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class PlayerTasks {
    private final Plugin plugin;
    private final TaskScheduler scheduler;

    public PlayerTasks(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = new TaskScheduler(plugin);
    }

    public void run(Player player, Runnable task) {
        run(player, task, () -> { });
    }

    public void run(Player player, Runnable task, Runnable retired) {
        submit(player, task, retired);
    }

    public TaskHandle submit(Player player, Runnable task) {
        return submit(player, task, () -> { });
    }

    public TaskHandle submit(Player player, Runnable task, Runnable retired) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(retired, "retired");
        UUID playerId = player.getUniqueId();
        Runnable unavailable = once(() -> {
            plugin.getLogger().log(Level.FINE, "Player retired before scheduled task: {0}", playerId);
            retired.run();
        });

        return scheduler.runAtEntity(player, () -> {
            if (player.isOnline()) {
                task.run();
            } else {
                unavailable.run();
            }
        }, unavailable);
    }

    public TaskScheduler scheduler() {
        return scheduler;
    }

    private Runnable once(Runnable task) {
        AtomicBoolean invoked = new AtomicBoolean();
        return () -> {
            if (invoked.compareAndSet(false, true)) {
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Player task retired callback failed", exception);
                }
            }
        };
    }
}
