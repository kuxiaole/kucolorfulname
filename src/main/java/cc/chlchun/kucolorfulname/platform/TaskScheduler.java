package cc.chlchun.kucolorfulname.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Routes work to the correct Paper or Folia execution context.
 */
public final class TaskScheduler {
    private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50L);
    private static final Runnable NO_OP = () -> { };
    private static final boolean FOLIA = detectFolia();
    private static final TaskHandle COMPLETED = new FixedTaskHandle(false);
    private static final TaskHandle REJECTED = new FixedTaskHandle(true);

    private final Plugin plugin;

    public TaskScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    public TaskHandle runAtEntity(Entity entity, Runnable task) {
        return runAtEntity(entity, task, NO_OP);
    }

    /**
     * Runs on an entity's owning context. The unavailable callback is invoked
     * once if the entity retires or the server rejects the task. Folia retirement
     * callbacks are deferred out of the entity scheduler's critical section.
     */
    public TaskHandle runAtEntity(Entity entity, Runnable task, Runnable unavailable) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        OneShot completion = new OneShot(Objects.requireNonNull(unavailable, "unavailable"));
        if (!canSchedule(completion)) {
            return REJECTED;
        }

        Boolean owned = owns(entity, completion);
        if (owned == null) {
            return REJECTED;
        }
        if (owned) {
            executeEntityTask(entity, task, completion);
            return COMPLETED;
        }

        try {
            if (FOLIA) {
                ScheduledTask scheduled = entity.getScheduler().run(
                        plugin,
                        ignored -> executeEntityTask(entity, task, completion),
                        () -> completion.rejectDeferred(this)
                );
                if (scheduled == null) {
                    completion.reject(this);
                    return REJECTED;
                }
                return new FoliaTaskHandle(scheduled, completion);
            }
            BukkitTask scheduled = Bukkit.getScheduler().runTask(
                    plugin,
                    () -> executeEntityTask(entity, task, completion)
            );
            return new PaperTaskHandle(scheduled, completion);
        } catch (RuntimeException exception) {
            return reject(completion, "entity task", exception);
        }
    }

    public TaskHandle runAtEntityDelayed(Entity entity, Runnable task, long delayTicks) {
        return runAtEntityDelayed(entity, task, delayTicks, NO_OP);
    }

    public TaskHandle runAtEntityDelayed(
            Entity entity,
            Runnable task,
            long delayTicks,
            Runnable unavailable
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        OneShot completion = new OneShot(Objects.requireNonNull(unavailable, "unavailable"));
        if (!canSchedule(completion)) {
            return REJECTED;
        }
        long safeDelay = Math.max(1L, delayTicks);

        try {
            if (FOLIA) {
                ScheduledTask scheduled = entity.getScheduler().runDelayed(
                        plugin,
                        ignored -> executeEntityTask(entity, task, completion),
                        () -> completion.rejectDeferred(this),
                        safeDelay
                );
                if (scheduled == null) {
                    completion.reject(this);
                    return REJECTED;
                }
                return new FoliaTaskHandle(scheduled, completion);
            }
            BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> executeEntityTask(entity, task, completion),
                    safeDelay
            );
            return new PaperTaskHandle(scheduled, completion);
        } catch (RuntimeException exception) {
            return reject(completion, "delayed entity task", exception);
        }
    }

    public TaskHandle runAtEntityAtFixedRate(
            Entity entity,
            Runnable task,
            long initialDelayTicks,
            long periodTicks,
            Runnable retired
    ) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        OnceCallback retirement = new OnceCallback(Objects.requireNonNull(retired, "retired"));
        if (!plugin.isEnabled()) {
            retirement.invoke(this);
            return REJECTED;
        }
        long safeInitialDelay = Math.max(1L, initialDelayTicks);
        long safePeriod = Math.max(1L, periodTicks);

        try {
            if (FOLIA) {
                ScheduledTask scheduled = entity.getScheduler().runAtFixedRate(
                        plugin,
                        current -> {
                            if (!plugin.isEnabled() || !entity.isValid()) {
                                current.cancel();
                                retirement.invoke(this);
                                return;
                            }
                            task.run();
                        },
                        () -> retirement.invokeDeferred(this),
                        safeInitialDelay,
                        safePeriod
                );
                if (scheduled == null) {
                    retirement.invoke(this);
                    return REJECTED;
                }
                return new FoliaTaskHandle(scheduled, null);
            }

            AtomicReference<BukkitTask> reference = new AtomicReference<>();
            BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!plugin.isEnabled() || !entity.isValid()) {
                    BukkitTask current = reference.get();
                    if (current != null) {
                        current.cancel();
                    }
                    retirement.invoke(this);
                    return;
                }
                task.run();
            }, safeInitialDelay, safePeriod);
            reference.set(scheduled);
            return new PaperTaskHandle(scheduled, null);
        } catch (RuntimeException exception) {
            logRejection("repeating entity task", exception);
            retirement.invoke(this);
            return REJECTED;
        }
    }

    public TaskHandle runAtRegion(Location location, Runnable task) {
        return runAtRegion(location, task, NO_OP);
    }

    public TaskHandle runAtRegion(Location location, Runnable task, Runnable rejected) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(rejected, "rejected");
        RegionTarget target;
        try {
            target = RegionTarget.from(location);
        } catch (RuntimeException exception) {
            return reject(new OneShot(rejected), "region target capture", exception);
        }
        return runAtRegion(target.world(), target.chunkX(), target.chunkZ(), task, rejected);
    }

    public TaskHandle runAtRegion(World world, int chunkX, int chunkZ, Runnable task) {
        return runAtRegion(world, chunkX, chunkZ, task, NO_OP);
    }

    public TaskHandle runAtRegion(
            World world,
            int chunkX,
            int chunkZ,
            Runnable task,
            Runnable rejected
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        OneShot completion = new OneShot(Objects.requireNonNull(rejected, "rejected"));
        if (!canSchedule(completion)) {
            return REJECTED;
        }

        Boolean owned = owns(world, chunkX, chunkZ, completion);
        if (owned == null) {
            return REJECTED;
        }
        if (owned) {
            completion.execute(task);
            return COMPLETED;
        }

        try {
            if (FOLIA) {
                ScheduledTask scheduled = Bukkit.getRegionScheduler().run(
                        plugin,
                        world,
                        chunkX,
                        chunkZ,
                        ignored -> completion.executeIfEnabled(plugin, task, this)
                );
                return new FoliaTaskHandle(scheduled, completion);
            }
            BukkitTask scheduled = Bukkit.getScheduler().runTask(
                    plugin,
                    () -> completion.executeIfEnabled(plugin, task, this)
            );
            return new PaperTaskHandle(scheduled, completion);
        } catch (RuntimeException exception) {
            return reject(completion, "region task", exception);
        }
    }

    public TaskHandle runAtRegionDelayed(Location location, Runnable task, long delayTicks) {
        return runAtRegionDelayed(location, task, delayTicks, NO_OP);
    }

    public TaskHandle runAtRegionDelayed(
            Location location,
            Runnable task,
            long delayTicks,
            Runnable rejected
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(rejected, "rejected");
        RegionTarget target;
        try {
            target = RegionTarget.from(location);
        } catch (RuntimeException exception) {
            return reject(new OneShot(rejected), "delayed region target capture", exception);
        }
        return runAtRegionDelayed(
                target.world(), target.chunkX(), target.chunkZ(), task, delayTicks, rejected
        );
    }

    public TaskHandle runAtRegionDelayed(
            World world,
            int chunkX,
            int chunkZ,
            Runnable task,
            long delayTicks
    ) {
        return runAtRegionDelayed(world, chunkX, chunkZ, task, delayTicks, NO_OP);
    }

    public TaskHandle runAtRegionDelayed(
            World world,
            int chunkX,
            int chunkZ,
            Runnable task,
            long delayTicks,
            Runnable rejected
    ) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(task, "task");
        OneShot completion = new OneShot(Objects.requireNonNull(rejected, "rejected"));
        if (!canSchedule(completion)) {
            return REJECTED;
        }
        long safeDelay = Math.max(1L, delayTicks);

        try {
            if (FOLIA) {
                ScheduledTask scheduled = Bukkit.getRegionScheduler().runDelayed(
                        plugin,
                        world,
                        chunkX,
                        chunkZ,
                        ignored -> completion.executeIfEnabled(plugin, task, this),
                        safeDelay
                );
                return new FoliaTaskHandle(scheduled, completion);
            }
            BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> completion.executeIfEnabled(plugin, task, this),
                    safeDelay
            );
            return new PaperTaskHandle(scheduled, completion);
        } catch (RuntimeException exception) {
            return reject(completion, "delayed region task", exception);
        }
    }

    public TaskHandle runGlobal(Runnable task) {
        return runGlobal(task, NO_OP);
    }

    public TaskHandle runGlobal(Runnable task, Runnable rejected) {
        Objects.requireNonNull(task, "task");
        OneShot completion = new OneShot(Objects.requireNonNull(rejected, "rejected"));
        if (!canSchedule(completion)) {
            return REJECTED;
        }

        Boolean owned = ownsGlobal(completion);
        if (owned == null) {
            return REJECTED;
        }
        if (owned) {
            completion.execute(task);
            return COMPLETED;
        }

        try {
            if (FOLIA) {
                ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().run(
                        plugin,
                        ignored -> completion.executeIfEnabled(plugin, task, this)
                );
                return new FoliaTaskHandle(scheduled, completion);
            }
            BukkitTask scheduled = Bukkit.getScheduler().runTask(
                    plugin,
                    () -> completion.executeIfEnabled(plugin, task, this)
            );
            return new PaperTaskHandle(scheduled, completion);
        } catch (RuntimeException exception) {
            return reject(completion, "global task", exception);
        }
    }

    public TaskHandle runGlobalDelayed(Runnable task, long delayTicks) {
        return runGlobalDelayed(task, delayTicks, NO_OP);
    }

    public TaskHandle runGlobalDelayed(Runnable task, long delayTicks, Runnable rejected) {
        Objects.requireNonNull(task, "task");
        OneShot completion = new OneShot(Objects.requireNonNull(rejected, "rejected"));
        if (!canSchedule(completion)) {
            return REJECTED;
        }
        long safeDelay = Math.max(1L, delayTicks);

        try {
            if (FOLIA) {
                ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runDelayed(
                        plugin,
                        ignored -> completion.executeIfEnabled(plugin, task, this),
                        safeDelay
                );
                return new FoliaTaskHandle(scheduled, completion);
            }
            BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> completion.executeIfEnabled(plugin, task, this),
                    safeDelay
            );
            return new PaperTaskHandle(scheduled, completion);
        } catch (RuntimeException exception) {
            return reject(completion, "delayed global task", exception);
        }
    }

    public TaskHandle runGlobalAtFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        return runGlobalAtFixedRate(task, initialDelayTicks, periodTicks, NO_OP);
    }

    public TaskHandle runGlobalAtFixedRate(
            Runnable task,
            long initialDelayTicks,
            long periodTicks,
            Runnable rejected
    ) {
        Objects.requireNonNull(task, "task");
        OnceCallback rejection = new OnceCallback(Objects.requireNonNull(rejected, "rejected"));
        if (!plugin.isEnabled()) {
            rejection.invoke(this);
            return REJECTED;
        }
        long safeInitialDelay = Math.max(1L, initialDelayTicks);
        long safePeriod = Math.max(1L, periodTicks);

        try {
            if (FOLIA) {
                ScheduledTask scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                        plugin,
                        current -> {
                            if (!plugin.isEnabled()) {
                                current.cancel();
                                rejection.invoke(this);
                                return;
                            }
                            task.run();
                        },
                        safeInitialDelay,
                        safePeriod
                );
                return new FoliaTaskHandle(scheduled, null);
            }
            BukkitTask scheduled = Bukkit.getScheduler().runTaskTimer(
                    plugin,
                    task,
                    safeInitialDelay,
                    safePeriod
            );
            return new PaperTaskHandle(scheduled, null);
        } catch (RuntimeException exception) {
            logRejection("repeating global task", exception);
            rejection.invoke(this);
            return REJECTED;
        }
    }

    public TaskHandle runAsync(Runnable task) {
        return runAsync(task, NO_OP);
    }

    public TaskHandle runAsync(Runnable task, Runnable rejected) {
        Objects.requireNonNull(task, "task");
        OneShot completion = new OneShot(Objects.requireNonNull(rejected, "rejected"));
        if (!canSchedule(completion)) {
            return REJECTED;
        }

        try {
            if (FOLIA) {
                ScheduledTask scheduled = Bukkit.getAsyncScheduler().runNow(
                        plugin,
                        ignored -> completion.executeIfEnabled(plugin, task, this)
                );
                return new FoliaTaskHandle(scheduled, completion);
            }
            BukkitTask scheduled = Bukkit.getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> completion.executeIfEnabled(plugin, task, this)
            );
            return new PaperTaskHandle(scheduled, completion);
        } catch (RuntimeException exception) {
            return reject(completion, "async task", exception);
        }
    }

    public TaskHandle runAsyncDelayed(Runnable task, long delay, TimeUnit unit) {
        return runAsyncDelayed(task, delay, unit, NO_OP);
    }

    public TaskHandle runAsyncDelayed(
            Runnable task,
            long delay,
            TimeUnit unit,
            Runnable rejected
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        OneShot completion = new OneShot(Objects.requireNonNull(rejected, "rejected"));
        if (!canSchedule(completion)) {
            return REJECTED;
        }
        if (delay <= 0L) {
            return runAsync(task, rejected);
        }

        try {
            if (FOLIA) {
                ScheduledTask scheduled = Bukkit.getAsyncScheduler().runDelayed(
                        plugin,
                        ignored -> completion.executeIfEnabled(plugin, task, this),
                        delay,
                        unit
                );
                return new FoliaTaskHandle(scheduled, completion);
            }
            BukkitTask scheduled = Bukkit.getScheduler().runTaskLaterAsynchronously(
                    plugin,
                    () -> completion.executeIfEnabled(plugin, task, this),
                    toTicks(delay, unit)
            );
            return new PaperTaskHandle(scheduled, completion);
        } catch (RuntimeException exception) {
            return reject(completion, "delayed async task", exception);
        }
    }

    public TaskHandle runForSender(CommandSender sender, Runnable task) {
        return runForSender(sender, task, NO_OP);
    }

    public TaskHandle runForSender(CommandSender sender, Runnable task, Runnable unavailable) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unavailable, "unavailable");
        try {
            return runForSender(captureSender(sender), task, unavailable);
        } catch (RuntimeException exception) {
            return reject(new OneShot(unavailable), "sender context capture", exception);
        }
    }

    /**
     * Captures immutable routing information for later use by an async
     * continuation. Call this while still in the command sender's context.
     */
    public SenderContext captureSender(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        if (sender instanceof Entity entity) {
            return new EntitySenderContext(entity);
        }
        if (sender instanceof BlockCommandSender blockSender) {
            Block block = blockSender.getBlock();
            return new RegionSenderContext(
                    block.getWorld(), block.getX() >> 4, block.getZ() >> 4
            );
        }
        return GlobalSenderContext.INSTANCE;
    }

    public TaskHandle runForSender(SenderContext context, Runnable task) {
        return runForSender(context, task, NO_OP);
    }

    public TaskHandle runForSender(SenderContext context, Runnable task, Runnable unavailable) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unavailable, "unavailable");
        if (context instanceof EntitySenderContext entityContext) {
            return runAtEntity(entityContext.entity(), task, unavailable);
        }
        if (context instanceof RegionSenderContext regionContext) {
            return runAtRegion(
                    regionContext.world(),
                    regionContext.chunkX(),
                    regionContext.chunkZ(),
                    task,
                    unavailable
            );
        }
        if (context == GlobalSenderContext.INSTANCE) {
            return runGlobal(task, unavailable);
        }
        throw new IllegalArgumentException("Unknown sender context implementation: " + context.getClass());
    }

    public TaskHandle runForOnlinePlayer(UUID playerId, Consumer<? super Player> task) {
        return runForOnlinePlayer(playerId, task, NO_OP);
    }

    public TaskHandle runForOnlinePlayer(
            UUID playerId,
            Consumer<? super Player> task,
            Runnable unavailable
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(task, "task");
        OnceCallback fallback = new OnceCallback(Objects.requireNonNull(unavailable, "unavailable"));
        CompositeTaskHandle composite = new CompositeTaskHandle();
        TaskHandle lookup = runGlobal(() -> {
            try {
                Player player = Bukkit.getPlayer(playerId);
                if (player == null) {
                    fallback.invoke(this);
                    return;
                }
                composite.add(runAtEntity(player, () -> {
                    if (player.isOnline() && playerId.equals(player.getUniqueId())) {
                        task.accept(player);
                    } else {
                        fallback.invoke(this);
                    }
                }, () -> fallback.invoke(this)));
            } catch (RuntimeException exception) {
                logRejection("online-player lookup", exception);
                fallback.invoke(this);
            } finally {
                composite.seal();
            }
        }, () -> {
            fallback.invoke(this);
            composite.seal();
        });
        composite.add(lookup);
        return composite;
    }

    /**
     * Safely fans out work using immutable UUIDs held by an online-player index.
     */
    public TaskHandle runForEachOnlinePlayer(
            OnlinePlayerIndex index,
            Consumer<? super Player> task
    ) {
        return runForEachOnlinePlayer(index, task, ignored -> { });
    }

    public TaskHandle runForEachOnlinePlayer(
            OnlinePlayerIndex index,
            Consumer<? super Player> task,
            Consumer<? super UUID> unavailable
    ) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unavailable, "unavailable");
        Set<UUID> playerIds = Set.copyOf(index.snapshotIds());
        CompositeTaskHandle composite = new CompositeTaskHandle();
        TaskHandle coordinator = runGlobal(() -> {
            try {
                for (UUID playerId : playerIds) {
                    OnceCallback fallback = new OnceCallback(() -> unavailable.accept(playerId));
                    try {
                        Player player = Bukkit.getPlayer(playerId);
                        if (player == null) {
                            fallback.invoke(this);
                            continue;
                        }
                        composite.add(runAtEntity(player, () -> {
                            if (player.isOnline() && playerId.equals(player.getUniqueId())) {
                                task.accept(player);
                            } else {
                                fallback.invoke(this);
                            }
                        }, () -> fallback.invoke(this)));
                    } catch (RuntimeException exception) {
                        logRejection("online-player lookup", exception);
                        fallback.invoke(this);
                    }
                }
            } finally {
                composite.seal();
            }
        }, composite::seal);
        composite.add(coordinator);
        return composite;
    }

    /**
     * Takes the Bukkit online-player snapshot on the global context, then
     * schedules each player independently on its entity context.
     */
    public TaskHandle runForEachOnlinePlayer(Consumer<? super Player> task) {
        Objects.requireNonNull(task, "task");
        CompositeTaskHandle composite = new CompositeTaskHandle();
        TaskHandle coordinator = runGlobal(() -> {
            try {
                Player[] players = Bukkit.getOnlinePlayers().toArray(Player[]::new);
                for (Player player : players) {
                    composite.add(runAtEntity(player, () -> {
                        if (player.isOnline()) {
                            task.accept(player);
                        }
                    }));
                }
            } finally {
                composite.seal();
            }
        }, composite::seal);
        composite.add(coordinator);
        return composite;
    }

    public TaskHandle runForEachOnlinePlayer(
            Consumer<? super Player> task,
            Consumer<? super UUID> unavailable
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unavailable, "unavailable");
        CompositeTaskHandle composite = new CompositeTaskHandle();
        TaskHandle coordinator = runGlobal(() -> {
            try {
                Player[] players = Bukkit.getOnlinePlayers().toArray(Player[]::new);
                for (Player player : players) {
                    UUID playerId = player.getUniqueId();
                    OnceCallback fallback = new OnceCallback(() -> unavailable.accept(playerId));
                    composite.add(runAtEntity(player, () -> {
                        if (player.isOnline()) {
                            task.accept(player);
                        } else {
                            fallback.invoke(this);
                        }
                    }, () -> fallback.invoke(this)));
                }
            } finally {
                composite.seal();
            }
        }, composite::seal);
        composite.add(coordinator);
        return composite;
    }

    private boolean canSchedule(OneShot completion) {
        if (plugin.isEnabled()) {
            return true;
        }
        completion.reject(this);
        return false;
    }

    private void executeEntityTask(Entity entity, Runnable task, OneShot completion) {
        if (!plugin.isEnabled() || !entity.isValid()) {
            completion.reject(this);
            return;
        }
        completion.execute(task);
    }

    private Boolean owns(Entity entity, OneShot completion) {
        try {
            return FOLIA ? Bukkit.isOwnedByCurrentRegion(entity) : Bukkit.isPrimaryThread();
        } catch (RuntimeException exception) {
            reject(completion, "entity ownership check", exception);
            return null;
        }
    }

    private Boolean owns(World world, int chunkX, int chunkZ, OneShot completion) {
        try {
            return FOLIA
                    ? Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)
                    : Bukkit.isPrimaryThread();
        } catch (RuntimeException exception) {
            reject(completion, "region ownership check", exception);
            return null;
        }
    }

    private Boolean ownsGlobal(OneShot completion) {
        try {
            return FOLIA ? Bukkit.isGlobalTickThread() : Bukkit.isPrimaryThread();
        } catch (RuntimeException exception) {
            reject(completion, "global ownership check", exception);
            return null;
        }
    }

    private TaskHandle reject(OneShot completion, String taskType, RuntimeException exception) {
        logRejection(taskType, exception);
        completion.reject(this);
        return REJECTED;
    }

    private void logRejection(String taskType, RuntimeException exception) {
        plugin.getLogger().log(Level.FINE, "Server rejected " + taskType, exception);
    }

    private void logTaskFailure(String taskType, Throwable failure) {
        plugin.getLogger().log(Level.SEVERE, "Uncaught failure in " + taskType, failure);
    }

    private void invokeCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Task rejection callback failed", exception);
        }
    }

    private void deferCallback(Runnable callback) {
        ForkJoinPool.commonPool().execute(() -> invokeCallback(callback));
    }

    private static long toTicks(long delay, TimeUnit unit) {
        long nanos = unit.toNanos(delay);
        if (nanos <= 0L) {
            return 1L;
        }
        long ticks = nanos / TICK_NANOS;
        if (nanos % TICK_NANOS != 0L) {
            ticks++;
        }
        return Math.max(1L, ticks);
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private record RegionTarget(World world, int chunkX, int chunkZ) {
        private static RegionTarget from(Location location) {
            Objects.requireNonNull(location, "location");
            World world = Objects.requireNonNull(location.getWorld(), "location.world");
            return new RegionTarget(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
    }

    /**
     * An opaque command-sender routing snapshot created by
     * {@link #captureSender(CommandSender)}.
     */
    public interface SenderContext { }

    private record EntitySenderContext(Entity entity) implements SenderContext {
        private EntitySenderContext {
            Objects.requireNonNull(entity, "entity");
        }
    }

    private record RegionSenderContext(World world, int chunkX, int chunkZ) implements SenderContext {
        private RegionSenderContext {
            Objects.requireNonNull(world, "world");
        }
    }

    private enum GlobalSenderContext implements SenderContext {
        INSTANCE
    }

    private final class OneShot {
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final Runnable unavailable;
        private volatile boolean done;

        private OneShot(Runnable unavailable) {
            this.unavailable = unavailable;
        }

        private void execute(Runnable task) {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                task.run();
            } finally {
                done = true;
            }
        }

        private void executeIfEnabled(Plugin owner, Runnable task, TaskScheduler scheduler) {
            if (owner.isEnabled()) {
                execute(task);
            } else {
                reject(scheduler);
            }
        }

        private void reject(TaskScheduler scheduler) {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            done = true;
            scheduler.invokeCallback(unavailable);
        }

        private void rejectDeferred(TaskScheduler scheduler) {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            done = true;
            scheduler.deferCallback(unavailable);
        }

        private void cancel() {
            if (claimed.compareAndSet(false, true)) {
                done = true;
            }
        }

        private boolean isDone() {
            return done;
        }
    }

    private static final class OnceCallback {
        private final AtomicBoolean invoked = new AtomicBoolean();
        private final Runnable callback;

        private OnceCallback(Runnable callback) {
            this.callback = callback;
        }

        private void invoke(TaskScheduler scheduler) {
            if (invoked.compareAndSet(false, true)) {
                scheduler.invokeCallback(callback);
            }
        }

        private void invokeDeferred(TaskScheduler scheduler) {
            if (invoked.compareAndSet(false, true)) {
                scheduler.deferCallback(callback);
            }
        }

    }

    private static final class FoliaTaskHandle implements TaskHandle {
        private final ScheduledTask task;
        private final OneShot completion;

        private FoliaTaskHandle(ScheduledTask task, OneShot completion) {
            this.task = task;
            this.completion = completion;
        }

        @Override
        public boolean cancel() {
            ScheduledTask.CancelledState state = task.cancel();
            boolean cancelled = state == ScheduledTask.CancelledState.CANCELLED_BY_CALLER
                    || state == ScheduledTask.CancelledState.NEXT_RUNS_CANCELLED;
            if (cancelled && completion != null) {
                completion.cancel();
            }
            return cancelled;
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }

        @Override
        public boolean isDone() {
            if (completion != null && completion.isDone()) {
                return true;
            }
            ScheduledTask.ExecutionState state = task.getExecutionState();
            return state == ScheduledTask.ExecutionState.FINISHED
                    || state == ScheduledTask.ExecutionState.CANCELLED;
        }
    }

    private static final class PaperTaskHandle implements TaskHandle {
        private final BukkitTask task;
        private final OneShot completion;

        private PaperTaskHandle(BukkitTask task, OneShot completion) {
            this.task = task;
            this.completion = completion;
        }

        @Override
        public boolean cancel() {
            if (task.isCancelled() || completion != null && completion.isDone()) {
                return false;
            }
            task.cancel();
            if (completion != null) {
                completion.cancel();
            }
            return true;
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }

        @Override
        public boolean isDone() {
            return task.isCancelled() || completion != null && completion.isDone();
        }
    }

    private static final class FixedTaskHandle implements TaskHandle {
        private final boolean cancelled;

        private FixedTaskHandle(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean cancel() {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return true;
        }
    }

    private static final class CompositeTaskHandle implements TaskHandle {
        private final Queue<TaskHandle> children = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile boolean sealed;

        private void add(TaskHandle child) {
            if (cancelled.get()) {
                child.cancel();
                return;
            }
            children.add(child);
            if (cancelled.get() && children.remove(child)) {
                child.cancel();
            }
        }

        private void seal() {
            sealed = true;
        }

        @Override
        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            sealed = true;
            for (TaskHandle child : children) {
                child.cancel();
            }
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return sealed && children.stream().allMatch(TaskHandle::isDone);
        }
    }
}
