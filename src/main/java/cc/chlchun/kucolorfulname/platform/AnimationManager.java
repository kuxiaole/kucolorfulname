package cc.chlchun.kucolorfulname.platform;

import cc.chlchun.kucolorfulname.api.NameStyle;
import cc.chlchun.kucolorfulname.api.PlaceholderSnapshot;
import cc.chlchun.kucolorfulname.api.StyleType;
import cc.chlchun.kucolorfulname.config.ColorfulNameConfig;
import cc.chlchun.kucolorfulname.render.NameRenderer;
import cc.chlchun.kucolorfulname.service.ColorfulNameService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Produces shared animation frames and lets entity-owned consumers send only the newest batch. */
public final class AnimationManager {
    private static final long NMS_VIEWER_RETRY_NANOS = 1_000_000_000L;
    private static final int NMS_SEND_FAILURE_LIMIT = 3;
    private final ColorfulNameService service;
    private final Supplier<ColorfulNameConfig> config;
    private final NameRenderer renderer;
    private final TaskScheduler scheduler;
    private final OnlinePlayerIndex onlinePlayers;
    private final Logger logger;
    private final Optional<NmsTabListBridge> nmsBridge;
    private final ConcurrentMap<UUID, TaskHandle> consumers = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Component> lastFrames = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> nmsViewerRetryAfter = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Integer> nmsViewerFailures = new ConcurrentHashMap<>();
    private final Set<UUID> dynamicSubjects = ConcurrentHashMap.newKeySet();
    private final AnimationDeliveryState<Component> deliveryState = new AnimationDeliveryState<>();
    private final AtomicReference<FrameBatch> latest = new AtomicReference<>(FrameBatch.EMPTY);
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong consumerGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean nmsFailed = new AtomicBoolean();
    private volatile TaskHandle producer;

    public AnimationManager(
            ColorfulNameService service,
            Supplier<ColorfulNameConfig> config,
            NameRenderer renderer,
            TaskScheduler scheduler,
            OnlinePlayerIndex onlinePlayers,
            Logger logger,
            String minecraftVersion
    ) {
        this.service = service;
        this.config = config;
        this.renderer = renderer;
        this.scheduler = scheduler;
        this.onlinePlayers = onlinePlayers;
        this.logger = logger;
        this.nmsBridge = NmsTabListBridge.create(minecraftVersion);
    }

    public synchronized void start() {
        if (closed.get() || !hasAnimatedStyles()) {
            return;
        }
        Instant now = Instant.now();
        for (UUID playerId : onlinePlayers.snapshotIds()) {
            service.cachedPlaceholderSnapshot(playerId)
                    .filter(snapshot -> isAnimated(snapshot, now))
                    .ifPresent(ignored -> dynamicSubjects.add(playerId));
        }
        if (!dynamicSubjects.isEmpty()) {
            startRuntime();
        }
    }

    public synchronized void reconfigure() {
        if (closed.get()) {
            return;
        }
        stopTasks();
        dynamicSubjects.clear();
        lastFrames.clear();
        deliveryState.clear();
        nmsViewerRetryAfter.clear();
        nmsViewerFailures.clear();
        nmsFailed.set(false);
        latest.set(new FrameBatch(
                sequence.incrementAndGet(), consumerGeneration.get(),
                Map.of(), Map.of(), null, false));
        start();
    }

    /** Updates the active-subject index from the player's entity-owned display refresh. */
    public synchronized void update(Player player, PlaceholderSnapshot snapshot) {
        if (closed.get() || !hasAnimatedStyles()
                || !player.getUniqueId().equals(snapshot.playerId())) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (isAnimated(snapshot, Instant.now())) {
            dynamicSubjects.add(playerId);
            startRuntime();
            track(player);
        } else {
            boolean hadDynamicState = dynamicSubjects.remove(playerId);
            hadDynamicState |= lastFrames.remove(playerId) != null;
            hadDynamicState |= deliveryState.hasPendingReset(playerId);
            hadDynamicState |= latest.get().names().containsKey(playerId);
            if (producer != null && hadDynamicState) {
                deliveryState.retainReset(
                        playerId, snapshot.renderedName(), Set.copyOf(consumers.keySet()));
            }
        }
    }

    public synchronized void track(Player player) {
        if (closed.get() || producer == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!requiresPerViewerConsumers()
                && !dynamicSubjects.contains(playerId)
                && !deliveryState.hasPendingReset(playerId)) {
            return;
        }
        if (consumers.containsKey(playerId)) {
            return;
        }
        int period = config.get().settings().animationRefreshTicks();
        long generation = consumerGeneration.get();
        AtomicLong lastSent = new AtomicLong(Long.MIN_VALUE);
        AtomicReference<TaskHandle> self = new AtomicReference<>();
        TaskHandle created = scheduler.runAtEntityAtFixedRate(
                player,
                () -> {
                    if (!closed.get() && generation == consumerGeneration.get()) {
                        consume(player, lastSent, generation);
                    }
                },
                1L,
                period,
                () -> {
                    TaskHandle current = self.get();
                    if (current != null && consumers.remove(playerId, current)) {
                        deliveryState.removePlayer(playerId);
                        clearViewerNmsFailure(playerId);
                    }
                }
        );
        self.set(created);
        if (created.isDone()) {
            return;
        }
        TaskHandle existing = consumers.putIfAbsent(playerId, created);
        if (existing != null) {
            created.cancel();
        } else {
            deliveryState.registerConsumer(playerId);
            if (created.isDone() && consumers.remove(playerId, created)) {
                deliveryState.removePlayer(playerId);
            }
        }
    }

    public synchronized void untrack(UUID playerId) {
        TaskHandle task = consumers.remove(playerId);
        if (task != null) {
            task.cancel();
        }
        dynamicSubjects.remove(playerId);
        lastFrames.remove(playerId);
        deliveryState.removePlayer(playerId);
        removeFromLatest(playerId);
        clearViewerNmsFailure(playerId);
    }

    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopTasks();
        dynamicSubjects.clear();
        lastFrames.clear();
        deliveryState.clear();
        nmsViewerRetryAfter.clear();
        nmsViewerFailures.clear();
        latest.set(FrameBatch.EMPTY);
    }

    private synchronized void produceFrame() {
        if (closed.get()) {
            return;
        }
        ColorfulNameConfig currentConfig = config.get();
        Instant now = Instant.now();
        long tick = System.currentTimeMillis() / 50L;
        Set<UUID> online = Set.copyOf(onlinePlayers.snapshotIds());
        Set<UUID> subjects = Set.copyOf(dynamicSubjects);
        Map<UUID, Component> currentFrames = new LinkedHashMap<>();

        for (UUID playerId : subjects) {
            if (!online.contains(playerId)) {
                dynamicSubjects.remove(playerId);
                continue;
            }
            Optional<PlaceholderSnapshot> cached = service.cachedPlaceholderSnapshot(playerId);
            if (cached.isEmpty()) {
                dynamicSubjects.remove(playerId);
                continue;
            }
            PlaceholderSnapshot snapshot = cached.get();
            NameStyle style = snapshot.activeStyleAt(now)
                    .map(currentConfig.styles()::get)
                    .filter(candidate -> candidate.type() == StyleType.ANIMATED)
                    .orElse(null);
            if (style != null) {
                currentFrames.put(playerId,
                        renderer.renderAtTick(snapshot.playerName(), style, tick));
            } else {
                dynamicSubjects.remove(playerId);
            }
        }

        Map<UUID, Component> newResets = new LinkedHashMap<>();
        for (UUID previous : Set.copyOf(lastFrames.keySet())) {
            if (online.contains(previous) && !currentFrames.containsKey(previous)) {
                String fallbackName = onlinePlayers.entry(previous)
                        .map(OnlinePlayerIndex.Entry::name)
                        .orElse(previous.toString());
                Component reset = service.cachedPlaceholderSnapshot(previous)
                        .map(snapshot -> service.renderActive(previous, snapshot.playerName()))
                        .orElseGet(() -> Component.text(fallbackName));
                newResets.put(previous, reset);
            }
        }

        deliveryState.cancelResetsFor(currentFrames.keySet());
        deliveryState.retainOnline(online);
        Set<UUID> currentConsumers = Set.copyOf(consumers.keySet());
        newResets.forEach((playerId, component) ->
                deliveryState.retainReset(playerId, component, currentConsumers));
        lastFrames.clear();
        lastFrames.putAll(currentFrames);
        AnimationDeliveryState.DeliverySnapshot<Component> delivery =
                deliveryState.snapshot(currentFrames);
        Map<UUID, Component> frameNames = delivery.names();

        boolean tabList = currentConfig.settings().applyToTabList();
        FrameBatch previous = latest.get();
        boolean changed = !frameNames.equals(previous.names())
                || !delivery.resetVersions().equals(previous.resetVersions())
                || tabList != previous.tabList();
        if (!changed) {
            if (frameNames.isEmpty() && dynamicSubjects.isEmpty()) {
                stopIdleRuntime();
            }
            return;
        }

        NmsTabListBridge.PreparedBatch prepared = null;
        if (tabList && !frameNames.isEmpty()
                && currentConfig.settings().nmsTabBatching()
                && !nmsFailed.get()
                && nmsBridge.isPresent()) {
            try {
                prepared = nmsBridge.get().prepare(frameNames);
            } catch (RuntimeException exception) {
                disableNms(exception);
            }
        }
        if (closed.get()) {
            return;
        }
        latest.set(new FrameBatch(
                sequence.incrementAndGet(), consumerGeneration.get(),
                frameNames, delivery.resetVersions(), prepared, tabList));
        if (frameNames.isEmpty() && dynamicSubjects.isEmpty()) {
            stopIdleRuntime();
        }
    }

    private void consume(Player player, AtomicLong lastSent, long generation) {
        if (closed.get() || generation != consumerGeneration.get()) {
            return;
        }
        FrameBatch batch = latest.get();
        if (batch.generation() != generation || batch.sequence() <= lastSent.get()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Component ownName = batch.names().get(playerId);
        NmsTabListBridge.PreparedBatch prepared = batch.prepared();
        if (batch.tabList() && prepared != null && !nmsFailed.get()) {
            Long retryAfter = nmsViewerRetryAfter.get(playerId);
            if (retryAfter != null && System.nanoTime() < retryAfter) {
                return;
            }
            if (generation != consumerGeneration.get()) {
                return;
            }
            try {
                nmsBridge.orElseThrow().applyAndSend(player, ownName, prepared);
                if (generation != consumerGeneration.get()) {
                    return;
                }
                clearViewerNmsFailure(playerId);
                completeDelivery(playerId, batch, lastSent, generation);
                return;
            } catch (RuntimeException exception) {
                if (generation != consumerGeneration.get()) {
                    return;
                }
                boolean globallyDisabled = reportViewerNmsFailure(playerId, exception);
                applyOwnName(player, ownName, batch.tabList(), true, generation);
                if (!globallyDisabled) {
                    return;
                }
            }
        }

        applyOwnName(player, ownName, batch.tabList(), false, generation);
        completeDelivery(playerId, batch, lastSent, generation);
    }

    private void applyOwnName(
            Player player,
            Component ownName,
            boolean tabList,
            boolean forceTabUpdate,
            long generation
    ) {
        if (ownName == null || generation != consumerGeneration.get()) {
            return;
        }
        if (!ownName.equals(player.displayName())) {
            player.displayName(ownName);
        }
        if (tabList && (forceTabUpdate || !ownName.equals(player.playerListName()))) {
            player.playerListName(ownName);
        }
    }

    private void completeDelivery(
            UUID playerId,
            FrameBatch batch,
            AtomicLong lastSent,
            long generation
    ) {
        if (generation != consumerGeneration.get() || batch.generation() != generation) {
            return;
        }
        if (!batch.resetVersions().isEmpty()) {
            deliveryState.acknowledge(playerId, batch.resetVersions());
        }
        lastSent.set(batch.sequence());
    }

    private void disableNms(RuntimeException exception) {
        if (nmsFailed.compareAndSet(false, true)) {
            nmsViewerRetryAfter.clear();
            nmsViewerFailures.clear();
            logger.log(Level.WARNING,
                    "Minecraft 26.2 NMS tab batching failed; using Bukkit broadcasts.", exception);
            pruneConsumersForBukkitFallback();
        }
    }

    private boolean reportViewerNmsFailure(UUID playerId, RuntimeException exception) {
        nmsViewerRetryAfter.put(playerId, System.nanoTime() + NMS_VIEWER_RETRY_NANOS);
        int failures = nmsViewerFailures.merge(playerId, 1, Integer::sum);
        if (failures >= NMS_SEND_FAILURE_LIMIT) {
            disableNms(exception);
            return true;
        }
        logger.log(Level.FINE,
                "Minecraft 26.2 NMS tab update failed for viewer " + playerId
                        + "; using Bukkit for this viewer and retrying in one second.",
                exception);
        return false;
    }

    private void clearViewerNmsFailure(UUID playerId) {
        nmsViewerRetryAfter.remove(playerId);
        nmsViewerFailures.remove(playerId);
    }

    private boolean hasAnimatedStyles() {
        return config.get().styles().values().stream()
                .anyMatch(style -> style.type() == StyleType.ANIMATED);
    }

    private boolean isAnimated(PlaceholderSnapshot snapshot, Instant now) {
        return snapshot.activeStyleAt(now)
                .map(config.get().styles()::get)
                .filter(style -> style.type() == StyleType.ANIMATED)
                .isPresent();
    }

    private boolean requiresPerViewerConsumers() {
        ColorfulNameConfig current = config.get();
        return current.settings().applyToTabList()
                && current.settings().nmsTabBatching()
                && nmsBridge.isPresent()
                && !nmsFailed.get();
    }

    private void pruneConsumersForBukkitFallback() {
        Set<UUID> required = ConcurrentHashMap.newKeySet();
        required.addAll(dynamicSubjects);
        required.addAll(deliveryState.pendingSubjects());
        consumers.forEach((playerId, task) -> {
            if (!required.contains(playerId) && consumers.remove(playerId, task)) {
                task.cancel();
                deliveryState.unregisterConsumer(playerId);
                clearViewerNmsFailure(playerId);
            }
        });
    }

    private void removeFromLatest(UUID playerId) {
        latest.updateAndGet(batch -> {
            if (!batch.names().containsKey(playerId)
                    && !batch.resetVersions().containsKey(playerId)) {
                return batch;
            }
            Map<UUID, Component> names = new LinkedHashMap<>(batch.names());
            Map<UUID, Long> resetVersions = new LinkedHashMap<>(batch.resetVersions());
            names.remove(playerId);
            resetVersions.remove(playerId);
            return new FrameBatch(
                    batch.sequence(), batch.generation(), names, resetVersions, null, batch.tabList());
        });
    }

    private void startRuntime() {
        if (producer != null || dynamicSubjects.isEmpty()) {
            return;
        }
        int period = config.get().settings().animationRefreshTicks();
        producer = scheduler.runGlobalAtFixedRate(this::produceFrame, 1L, period);
        scheduler.runForEachOnlinePlayer(onlinePlayers, this::track);
        if (config.get().settings().nmsTabBatching() && nmsBridge.isEmpty()) {
            logger.warning("Minecraft 26.2 NMS tab batching is unavailable; using Bukkit broadcasts.");
        }
    }

    private void stopIdleRuntime() {
        stopTasks();
        nmsViewerRetryAfter.clear();
        nmsViewerFailures.clear();
    }

    private void stopTasks() {
        consumerGeneration.incrementAndGet();
        TaskHandle currentProducer = producer;
        producer = null;
        if (currentProducer != null) {
            currentProducer.cancel();
        }
        consumers.values().forEach(TaskHandle::cancel);
        consumers.clear();
    }

    private record FrameBatch(
            long sequence,
            long generation,
            Map<UUID, Component> names,
            Map<UUID, Long> resetVersions,
            NmsTabListBridge.PreparedBatch prepared,
            boolean tabList
    ) {
        private static final FrameBatch EMPTY = new FrameBatch(
                0L, 0L, Map.of(), Map.of(), null, false);

        private FrameBatch {
            names = Map.copyOf(names);
            resetVersions = Map.copyOf(resetVersions);
        }
    }
}
