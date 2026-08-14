package cc.chlchun.kucolorfulname.service;

import cc.chlchun.kucolorfulname.api.ActivationResult;
import cc.chlchun.kucolorfulname.api.ColorfulNameApi;
import cc.chlchun.kucolorfulname.api.GrantAuditEntry;
import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.NameStyle;
import cc.chlchun.kucolorfulname.api.PlayerNameSnapshot;
import cc.chlchun.kucolorfulname.api.PlaceholderSnapshot;
import cc.chlchun.kucolorfulname.api.PlaceholderStatus;
import cc.chlchun.kucolorfulname.api.PlaceholderStyleState;
import cc.chlchun.kucolorfulname.api.StyleUnlockSource;
import cc.chlchun.kucolorfulname.api.event.ColorfulNameActivateEvent;
import cc.chlchun.kucolorfulname.api.event.ColorfulNameChangedEvent;
import cc.chlchun.kucolorfulname.api.event.ColorfulNameDeactivateEvent;
import cc.chlchun.kucolorfulname.api.event.ColorfulNameExtendEvent;
import cc.chlchun.kucolorfulname.api.event.ColorfulNameGrantEvent;
import cc.chlchun.kucolorfulname.api.event.ColorfulNameRevokeEvent;
import cc.chlchun.kucolorfulname.config.ColorfulNameConfig;
import cc.chlchun.kucolorfulname.config.PermissionGroup;
import cc.chlchun.kucolorfulname.render.NameRenderer;
import cc.chlchun.kucolorfulname.platform.PlayerTasks;
import cc.chlchun.kucolorfulname.storage.NameRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class ColorfulNameService implements ColorfulNameApi {
    private static final String STYLE_PERMISSION_BYPASS =
            "kucolorfulname.admin.style-permission-bypass";
    private final NameRepository repository;
    private final Supplier<ColorfulNameConfig> config;
    private final NameRenderer renderer;
    private final Plugin plugin;
    private final PlayerTasks playerTasks;
    private final PlayerCacheStore cache = new PlayerCacheStore();
    private final PlayerOperationQueue operations = new PlayerOperationQueue();
    private final ConcurrentMap<UUID, LoadRequest> loads = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, CompletableFuture<Boolean>> defaultEquips =
            new ConcurrentHashMap<>();
    private final DefaultStyleController defaultStyles = new DefaultStyleController();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ColorfulNameService(
            NameRepository repository,
            Supplier<ColorfulNameConfig> config,
            NameRenderer renderer,
            Plugin plugin,
            PlayerTasks playerTasks
    ) {
        this.repository = repository;
        this.config = config;
        this.renderer = renderer;
        this.plugin = plugin;
        this.playerTasks = playerTasks;
    }

    @Override
    public Collection<NameStyle> styles() {
        return config.get().styles().values();
    }

    @Override
    public Optional<NameStyle> style(String key) {
        return Optional.ofNullable(config.get().styles().get(normalize(key)));
    }

    @Override
    public CompletionStage<List<NameGrant>> grants(UUID playerId) {
        return repository.grants(playerId).thenApply(grants -> validGrants(grants, Instant.now()));
    }

    @Override
    public CompletionStage<PlayerNameSnapshot> snapshot(UUID playerId) {
        return repository.snapshot(playerId).thenApply(snapshot -> new PlayerNameSnapshot(
                playerId, validGrants(snapshot.grants(), Instant.now()), snapshot.activeStyleKey()));
    }

    @Override
    public CompletionStage<Optional<NameGrant>> activeGrant(UUID playerId) {
        return repository.snapshot(playerId).thenApply(snapshot -> snapshot.activeStyleKey()
                .flatMap(active -> snapshot.grants().stream()
                        .filter(grant -> grant.styleKey().equals(active) && grant.validAt(Instant.now()))
                        .findFirst()));
    }

    @Override
    public CompletionStage<NameGrant> grant(
            UUID playerId,
            String styleKey,
            Duration duration,
            UUID grantedBy,
            String source
    ) {
        String normalized = normalize(styleKey);
        if (!config.get().styles().containsKey(normalized)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown style: " + styleKey));
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Duration must be positive"));
        }
        Instant now = Instant.now();
        NameGrant grant = new NameGrant(
                UUID.randomUUID(),
                playerId,
                normalized,
                now,
                now.plus(duration),
                grantedBy,
                source == null || source.isBlank() ? "api" : source
        );
        PlayerCacheStore.Session session = cache.current(playerId).orElse(null);
        return operations.submit(playerId, () -> repository.saveGrant(grant).thenApply(saved -> {
            if (session != null) {
                cache.update(session, state -> state.withGrants(replaceGrant(state.grants(), saved)));
                refreshPlaceholderIfOnline(playerId);
            }
            fireGlobal(() -> Bukkit.getPluginManager().callEvent(new ColorfulNameGrantEvent(false, saved)));
            return saved;
        }));
    }

    @Override
    public CompletionStage<Boolean> revoke(UUID playerId, String styleKey) {
        return revoke(playerId, styleKey, null, "api");
    }

    @Override
    public CompletionStage<Boolean> revoke(UUID playerId, String styleKey, UUID actorId, String source) {
        String normalized = normalize(styleKey);
        PlayerCacheStore.Session session = cache.current(playerId).orElse(null);
        return operations.submit(playerId, () -> repository.deleteGrant(
                playerId, normalized, actorId, source).thenApply(removed -> {
            if (removed) {
                if (session != null) {
                    cache.update(session, state -> state.withGrants(state.grants().stream()
                            .filter(grant -> !grant.styleKey().equals(normalized))
                            .toList()));
                    refreshPlaceholderIfOnline(playerId);
                }
                fireGlobal(() -> Bukkit.getPluginManager().callEvent(
                        new ColorfulNameRevokeEvent(playerId, normalized, actorId, normalizeSource(source))));
            }
            return removed;
        }));
    }

    @Override
    public CompletionStage<Optional<NameGrant>> extend(
            UUID playerId,
            String styleKey,
            Duration duration,
            UUID actorId,
            String source
    ) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Duration must be positive"));
        }
        String normalized = normalize(styleKey);
        PlayerCacheStore.Session session = cache.current(playerId).orElse(null);
        return operations.submit(playerId, () -> repository.extendGrant(
                playerId, normalized, duration, actorId, source).thenApply(extended -> {
            extended.ifPresent(grant -> {
                if (session != null) {
                    cache.update(session, state -> state.withGrants(replaceGrant(state.grants(), grant)));
                    refreshPlaceholderIfOnline(playerId);
                }
                fireGlobal(() -> Bukkit.getPluginManager().callEvent(
                        new ColorfulNameExtendEvent(grant, duration, actorId, normalizeSource(source))));
            });
            return extended;
        }));
    }

    @Override
    public CompletionStage<List<GrantAuditEntry>> audit(UUID playerId, int limit) {
        return repository.audit(playerId, limit);
    }

    @Override
    public CompletionStage<ActivationResult> activate(Player player, String styleKey) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Colorful-name service is closed"));
        }
        String normalized = normalize(styleKey);
        NameStyle style = config.get().styles().get(normalized);
        if (style == null) {
            return CompletableFuture.completedFuture(ActivationResult.UNKNOWN_STYLE);
        }
        UUID playerId = player.getUniqueId();
        long manualRevision = defaultStyles.beginManualSelection(playerId);
        cancelPendingDefaultEquip(playerId);
        CompletableFuture<ActivationResult> result = new CompletableFuture<>();
        load(playerId).whenComplete((ignored, error) -> {
            if (error != null) {
                defaultStyles.cancelManualSelection(playerId, manualRevision);
                result.completeExceptionally(error);
                return;
            }
            playerTasks.scheduler().runForOnlinePlayer(
                    playerId,
                    current -> activateLoaded(current, style, manualRevision, result),
                    () -> {
                        defaultStyles.cancelManualSelection(playerId, manualRevision);
                        result.completeExceptionally(new IllegalStateException("Player is no longer online"));
                    }
            );
        });
        return result;
    }

    private void activateLoaded(
            Player player,
            NameStyle style,
            long manualRevision,
            CompletableFuture<ActivationResult> result
    ) {
        UUID playerId = player.getUniqueId();
        if (defaultStyles.current(playerId, manualRevision).isEmpty()) {
            result.complete(ActivationResult.CANCELLED);
            return;
        }
        Instant now = Instant.now();
        List<NameGrant> grants = validGrants(cache.state(playerId).grants(), now).stream()
                .sorted(java.util.Comparator.comparing(NameGrant::grantedAt).reversed())
                .toList();
        Optional<NameGrant> owned = grants.stream()
                .filter(grant -> grant.styleKey().equals(style.key()))
                .findFirst();
        PermissionGroup group = config.get().groupFor(player);
        boolean permissionUnlocked = hasPermissionUnlock(player, group, style);
        boolean grantUnlocked = owned.isPresent();
        Optional<StyleUnlockSource> unlockSource = StyleUnlockSource.resolve(
                permissionUnlocked, grantUnlocked);
        if (unlockSource.isEmpty()) {
            defaultStyles.cancelManualSelection(playerId, manualRevision);
            result.complete(ActivationResult.NOT_UNLOCKED);
            return;
        }
        ColorfulNameActivateEvent event = new ColorfulNameActivateEvent(
                player, owned, style, unlockSource.get());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            defaultStyles.cancelManualSelection(playerId, manualRevision);
            result.complete(ActivationResult.CANCELLED);
            return;
        }
        if (!defaultStyles.prepareManualSelection(playerId, manualRevision, group)) {
            result.complete(ActivationResult.CANCELLED);
            return;
        }
        PlayerCacheStore.Session session = cache.current(playerId).orElse(null);
        cancelPendingDefaultEquip(playerId);
        operations.submit(playerId, () -> repository.setActiveStyle(
                playerId, style.key(), playerId, "player-activate")).whenComplete((ignored, error) -> {
            if (error != null) {
                defaultStyles.finishManualSelection(playerId, manualRevision);
                result.completeExceptionally(error);
                return;
            }
            if (session != null && defaultStyles.current(playerId, manualRevision).isPresent()) {
                cache.update(session, state -> state.withActiveStyle(style.key()));
            }
            defaultStyles.finishManualSelection(playerId, manualRevision);
            playerTasks.scheduler().runForOnlinePlayer(playerId, current -> {
                if (defaultStyles.current(playerId, manualRevision).isEmpty()
                        || (session != null && !cache.isCurrent(session))) {
                    result.complete(ActivationResult.ACTIVATED);
                    return;
                }
                refreshPlaceholderSnapshot(current);
                Bukkit.getPluginManager().callEvent(
                        new ColorfulNameChangedEvent(current, owned, style, unlockSource.get()));
                result.complete(ActivationResult.ACTIVATED);
            }, () -> result.complete(ActivationResult.ACTIVATED));
        });
    }

    @Override
    public CompletionStage<Void> deactivate(UUID playerId) {
        long manualRevision = defaultStyles.beginManualDeactivation(playerId);
        cancelPendingDefaultEquip(playerId);
        PlayerCacheStore.Session session = cache.current(playerId).orElse(null);
        AtomicReference<String> previous = new AtomicReference<>();
        CompletionStage<Void> operation = operations.submit(playerId, () -> repository.clearActiveStyle(
                        playerId, playerId, "api-deactivate")
                .thenRun(() -> {
                    if (defaultStyles.current(playerId, manualRevision).isEmpty()) {
                        return;
                    }
                    if (session != null) {
                        cache.update(session, state -> {
                            previous.set(state.activeStyleKey());
                            return state.withActiveStyle(null);
                        });
                        refreshPlaceholderIfOnline(playerId);
                    }
                    if (previous.get() != null) {
                        fireGlobal(() -> Bukkit.getPluginManager().callEvent(
                                new ColorfulNameDeactivateEvent(playerId, previous.get())));
                    }
                }));
        operation.whenComplete((ignored, error) ->
                defaultStyles.finishManualDeactivation(playerId, manualRevision));
        return operation;
    }

    @Override
    public Component renderActive(UUID playerId, String fallbackName) {
        PlayerCacheStore.State state = cache.state(playerId);
        String active = state.activeStyleKey();
        NameStyle style = active == null ? null : config.get().styles().get(active);
        if (style == null) {
            return Component.text(fallbackName);
        }
        Instant now = Instant.now();
        PlaceholderSnapshot placeholder = state.placeholder();
        if (placeholder != null) {
            boolean available = placeholder.activeStyleAt(now)
                    .filter(active::equals)
                    .isPresent();
            return available ? renderer.render(fallbackName, style)
                    : Component.text(fallbackName);
        }
        boolean grantUnlocked = state.grants().stream()
                .anyMatch(grant -> grant.styleKey().equals(active) && grant.validAt(now));
        return grantUnlocked ? renderer.render(fallbackName, style)
                : Component.text(fallbackName);
    }

    @Override
    public Component renderActive(Player player) {
        return refreshPlaceholderSnapshot(player).renderedName();
    }

    /**
     * Equips the selected group's configured default style when the player has no usable
     * selection. The decision is made on the player's entity scheduler; the database write is
     * serialized through the per-player operation queue.
     */
    public CompletionStage<Boolean> ensureDefaultStyle(Player player) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        CompletableFuture<Boolean> existing = defaultEquips.get(playerId);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        existing = defaultEquips.putIfAbsent(playerId, result);
        if (existing != null) {
            return existing;
        }
        long revision = defaultStyles.beginCheck(playerId).revision();
        result.whenComplete((ignored, error) -> defaultEquips.remove(playerId, result));
        CompletionStage<Void> loaded = isCached(playerId)
                ? CompletableFuture.completedFuture(null)
                : load(playerId);
        loaded.whenComplete((ignored, error) -> {
            if (result.isDone()) {
                return;
            }
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            playerTasks.scheduler().runForOnlinePlayer(
                    playerId,
                    current -> ensureDefaultStyleLoaded(current, revision, result),
                    () -> result.complete(false)
            );
        });
        return result;
    }

    private void ensureDefaultStyleLoaded(
            Player player,
            long revision,
            CompletableFuture<Boolean> result
    ) {
        try {
            UUID playerId = player.getUniqueId();
            if (result.isDone() || defaultEquips.get(playerId) != result) {
                return;
            }
            DefaultStyleController.State control = defaultStyles.current(playerId, revision)
                    .orElse(null);
            if (control == null || control.manualOperationPending()) {
                result.complete(false);
                return;
            }
            ColorfulNameConfig current = config.get();
            PermissionGroup group = current.groupFor(player);
            if (control.manuallyDisabled() && control.group() == null) {
                defaultStyles.transition(playerId, revision,
                        state -> state.evaluated(group, null, true));
                result.complete(false);
                return;
            }
            boolean groupChanged = control.groupChanged(group);
            boolean manuallyDisabled = control.manuallyDisabled() && !groupChanged;
            String defaultKey = group.defaultStyle();
            if (defaultKey.isBlank()) {
                boolean clearObsoleteAutomatic = control.automaticStyle() != null
                        && control.automaticStyle().equals(cache.state(playerId).activeStyleKey());
                if (clearObsoleteAutomatic) {
                    clearAutomaticStyle(playerId, revision, group, result);
                    return;
                }
                defaultStyles.transition(playerId, revision,
                        state -> state.evaluated(group, null, manuallyDisabled));
                result.complete(false);
                return;
            }
            if (manuallyDisabled) {
                result.complete(false);
                return;
            }
            NameStyle defaultStyle = current.styles().get(defaultKey);
            if (defaultStyle == null || !hasPermissionUnlock(player, group, defaultStyle)) {
                result.complete(false);
                return;
            }

            PlayerCacheStore.Session session = cache.current(playerId).orElse(null);
            if (session == null) {
                result.complete(false);
                return;
            }
            PlayerCacheStore.State cached = session.state().get();
            String activeKey = cached.activeStyleKey();
            boolean replaceAutomaticSelection = control.shouldReplaceAutomaticSelection(
                    activeKey, defaultKey);
            Instant now = Instant.now();
            List<NameGrant> grants = validGrants(cached.grants(), now);
            if (activeKey != null) {
                NameStyle activeStyle = current.styles().get(activeKey);
                if (!replaceAutomaticSelection
                        && activeStyle != null
                        && isStyleUsable(player, group, activeStyle, grants)) {
                    String retainedAutomatic = activeKey.equals(control.automaticStyle())
                            ? activeKey : null;
                    defaultStyles.transition(playerId, revision,
                            state -> state.evaluated(group, retainedAutomatic, false));
                    result.complete(false);
                    return;
                }
            }

            Optional<NameGrant> owned = grants.stream()
                    .filter(grant -> grant.styleKey().equals(defaultKey))
                    .findFirst();
            StyleUnlockSource unlockSource = StyleUnlockSource.resolve(
                    hasPermissionUnlock(player, group, defaultStyle), owned.isPresent())
                    .orElse(null);
            if (unlockSource == null) {
                result.complete(false);
                return;
            }
            if (defaultStyles.current(playerId, revision).isEmpty()) {
                result.complete(false);
                return;
            }
            ColorfulNameActivateEvent event = new ColorfulNameActivateEvent(
                    player, owned, defaultStyle, unlockSource);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                result.complete(false);
                return;
            }

            operations.submit(playerId, () -> {
                if (result.isDone()
                        || defaultEquips.get(playerId) != result
                        || defaultStyles.current(playerId, revision).isEmpty()) {
                    return CompletableFuture.completedFuture(false);
                }
                return repository.setActiveStyle(
                        playerId, defaultKey, playerId, "group-default").thenApply(ignored -> true);
            }).whenComplete((written, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                    return;
                }
                if (!Boolean.TRUE.equals(written)
                        || result.isDone()
                        || defaultEquips.get(playerId) != result
                        || !defaultStyles.transition(
                                playerId,
                                revision,
                                state -> state.evaluated(group, defaultKey, false))) {
                    result.complete(false);
                    return;
                }
                cache.update(session, state -> state.withActiveStyle(defaultKey));
                playerTasks.scheduler().runForOnlinePlayer(playerId, currentPlayer -> {
                    if (!cache.isCurrent(session)
                            || defaultStyles.current(playerId, revision).isEmpty()
                            || !group.equals(config.get().groupFor(currentPlayer))) {
                        result.complete(false);
                        return;
                    }
                    refreshPlaceholderSnapshot(currentPlayer);
                    Bukkit.getPluginManager().callEvent(new ColorfulNameChangedEvent(
                            currentPlayer, owned, defaultStyle, unlockSource));
                    result.complete(true);
                }, () -> result.complete(true));
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
    }

    private void clearAutomaticStyle(
            UUID playerId,
            long revision,
            PermissionGroup group,
            CompletableFuture<Boolean> result
    ) {
        PlayerCacheStore.Session session = cache.current(playerId).orElse(null);
        operations.submit(playerId, () -> {
            if (result.isDone()
                    || defaultEquips.get(playerId) != result
                    || defaultStyles.current(playerId, revision).isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            return repository.clearActiveStyle(
                    playerId, playerId, "group-default").thenApply(ignored -> true);
        }).whenComplete((cleared, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            if (!Boolean.TRUE.equals(cleared)
                    || result.isDone()
                    || defaultEquips.get(playerId) != result
                    || !defaultStyles.transition(playerId, revision,
                            state -> state.evaluated(group, null, false))) {
                result.complete(false);
                return;
            }
            if (session != null) {
                cache.update(session, state -> state.withActiveStyle(null));
            }
            refreshPlaceholderIfOnline(playerId);
            result.complete(true);
        });
    }

    private boolean isStyleUsable(
            Player player,
            PermissionGroup group,
            NameStyle style,
            List<NameGrant> validGrants
    ) {
        return hasPermissionUnlock(player, group, style)
                || validGrants.stream().anyMatch(grant -> grant.styleKey().equals(style.key()));
    }

    /**
     * Computes and publishes the read-only integration snapshot. Call this from the player's
     * scheduler because permission and player name lookups are performed while building it.
     */
    public PlaceholderSnapshot refreshPlaceholderSnapshot(Player player) {
        ColorfulNameConfig current = config.get();
        UUID playerId = player.getUniqueId();
        PlayerCacheStore.Session session = cache.current(playerId).orElse(null);
        if (session == null && !closed.get()) {
            session = cache.open(playerId);
            if (closed.get()) {
                cache.unload(playerId);
                session = null;
            }
        }
        PlayerCacheStore.State cached = session == null ? cache.state(playerId) : session.state().get();
        PermissionGroup group = current.groupFor(player);
        Instant now = Instant.now();
        List<NameGrant> grants = validGrants(cached.grants(), now).stream()
                .sorted(java.util.Comparator.comparing(NameGrant::grantedAt).reversed())
                .toList();

        boolean bypass = player.hasPermission(STYLE_PERMISSION_BYPASS);
        Set<String> permissionStyles = new HashSet<>(group.unlockedStyles());
        for (NameStyle style : current.styles().values()) {
            if (!style.hasPermissionUnlock()) {
                continue;
            }
            if (bypass || hasConfiguredStylePermission(player, style)) {
                permissionStyles.add(normalize(style.key()));
            }
        }

        Set<String> grantStyles = new HashSet<>();
        Map<String, NameGrant> grantsByStyle = new LinkedHashMap<>();
        for (NameGrant grant : grants) {
            grantsByStyle.putIfAbsent(grant.styleKey(), grant);
            grantStyles.add(grant.styleKey());
        }

        Map<String, PlaceholderStyleState> styleStates = new LinkedHashMap<>();
        for (NameStyle style : current.styles().values()) {
            String styleKey = normalize(style.key());
            boolean permissionUnlocked = permissionStyles.contains(styleKey);
            boolean grantUnlocked = grantStyles.contains(styleKey);
            Optional<StyleUnlockSource> source = StyleUnlockSource.resolve(
                    permissionUnlocked, grantUnlocked);
            Optional<Instant> expiresAt = Optional.ofNullable(grantsByStyle.get(styleKey))
                    .map(NameGrant::expiresAt);
            styleStates.put(styleKey, new PlaceholderStyleState(
                    styleKey,
                    source.isPresent(),
                    permissionUnlocked,
                    grantUnlocked,
                    expiresAt,
                    source
            ));
        }

        String selectedKey = cached.activeStyleKey();
        Optional<PlaceholderStyleState> selectedState = Optional.ofNullable(selectedKey)
                .map(styleStates::get);
        Optional<String> activeKey = selectedState.filter(state -> state.availableAt(now))
                .map(PlaceholderStyleState::styleKey);
        Optional<StyleUnlockSource> activeSource = selectedState.flatMap(state ->
                state.unlockSourceAt(now));
        Optional<NameGrant> activeGrant = Optional.ofNullable(grantsByStyle.get(selectedKey));
        PlaceholderStatus status;
        if (activeKey.isPresent()) {
            status = PlaceholderStatus.ACTIVE;
        } else if (selectedKey != null) {
            status = PlaceholderStatus.LOCKED;
        } else if (styleStates.values().stream().anyMatch(state -> state.availableAt(now))) {
            status = PlaceholderStatus.AVAILABLE;
        } else {
            status = PlaceholderStatus.NO_ACTIVE_STYLE;
        }

        Component renderedName = activeKey
                .map(current.styles()::get)
                .map(style -> renderer.render(player.getName(), style))
                .orElseGet(() -> Component.text(player.getName()));
        PlaceholderSnapshot snapshot = new PlaceholderSnapshot(
                playerId,
                player.getName(),
                renderedName,
                Optional.ofNullable(selectedKey),
                activeKey,
                activeGrant,
                activeSource,
                status,
                group.name(),
                grants.size(),
                styleStates,
                now
        );
        if (session != null && cached.loaded()) {
            cache.publishPlaceholder(session, cached, snapshot);
        }
        return snapshot;
    }

    @Override
    public CompletionStage<Void> refresh(UUID playerId) {
        if (closed.get()) {
            return closedFuture();
        }
        PlayerCacheStore.Session session = cache.open(playerId);
        if (closed.get()) {
            cache.unload(playerId);
            return closedFuture();
        }
        return loadSnapshot(session).thenApply(ignored -> null);
    }

    /**
     * Reloads a cached player's database snapshot and reports whether visible data changed.
     * Used by network-storage polling to avoid unnecessary entity scheduling and packets.
     */
    public CompletionStage<Boolean> synchronize(UUID playerId) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Colorful-name service is closed"));
        }
        PlayerCacheStore.Session session = cache.open(playerId);
        if (closed.get()) {
            cache.unload(playerId);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Colorful-name service is closed"));
        }
        return loadSnapshot(session);
    }

    public CompletionStage<Void> load(UUID playerId) {
        if (closed.get()) {
            return closedFuture();
        }
        PlayerCacheStore.Session session = cache.open(playerId);
        if (closed.get()) {
            cache.unload(playerId);
            return closedFuture();
        }
        while (true) {
            LoadRequest existing = loads.get(playerId);
            if (existing != null && existing.session() == session) {
                return existing.future();
            }
            LoadRequest request = new LoadRequest(session, new CompletableFuture<>());
            boolean installed = existing == null
                    ? loads.putIfAbsent(playerId, request) == null
                    : loads.replace(playerId, existing, request);
            if (installed) {
                loadSnapshot(session).whenComplete((ignored, failure) -> {
                    loads.remove(playerId, request);
                    if (failure == null) {
                        request.future().complete(null);
                    } else {
                        request.future().completeExceptionally(failure);
                    }
                });
                return request.future();
            }
        }
    }

    public void unload(UUID playerId) {
        defaultStyles.unload(playerId);
        cancelPendingDefaultEquip(playerId);
        cache.unload(playerId);
    }

    public void configurationReloaded() {
        defaultStyles.invalidateDefaultChecks();
        defaultEquips.forEach((playerId, pending) -> {
            if (defaultEquips.remove(playerId, pending)) {
                pending.complete(false);
            }
        });
    }

    public CompletionStage<Integer> removeExpired() {
        Instant now = Instant.now();
        return repository.removeExpired(now).thenApply(removed -> {
            for (PlayerCacheStore.Session session : cache.sessions()) {
                cache.update(session, state -> {
                    List<NameGrant> valid = validGrants(state.grants(), now);
                    if (valid.size() == state.grants().size()) {
                        return state;
                    }
                    return state.withGrants(valid);
                });
            }
            return removed;
        });
    }

    public int ownedCount(UUID playerId) {
        return cachedGrants(playerId).size();
    }

    @Override
    public List<NameGrant> cachedGrants(UUID playerId) {
        return validGrants(cache.state(playerId).grants(), Instant.now());
    }

    public Optional<String> activeStyleKey(UUID playerId) {
        return Optional.ofNullable(cache.state(playerId).activeStyleKey());
    }

    @Override
    public Optional<String> cachedActiveStyleKey(UUID playerId) {
        return activeStyleKey(playerId);
    }

    @Override
    public Optional<NameGrant> cachedActiveGrant(UUID playerId) {
        PlayerCacheStore.State state = cache.state(playerId);
        String active = state.activeStyleKey();
        if (active == null) return Optional.empty();
        Instant now = Instant.now();
        return state.grants().stream()
                .filter(grant -> grant.styleKey().equals(active) && grant.validAt(now))
                .findFirst();
    }

    @Override
    public boolean isCached(UUID playerId) {
        return cache.current(playerId).map(session -> session.state().get().loaded()).orElse(false);
    }

    @Override
    public Optional<PlaceholderSnapshot> cachedPlaceholderSnapshot(UUID playerId) {
        return Optional.ofNullable(cache.state(playerId).placeholder());
    }

    @Override
    public boolean canUseCached(Player player, String styleKey) {
        return unlockSourceCached(player, styleKey).isPresent();
    }

    @Override
    public boolean hasStylePermission(Player player, String styleKey) {
        NameStyle style = config.get().styles().get(normalize(styleKey));
        if (style == null) {
            return false;
        }
        return hasStylePermission(player, style);
    }

    private boolean hasStylePermission(Player player, NameStyle style) {
        return style.hasPermissionUnlock()
                && (player.hasPermission(STYLE_PERMISSION_BYPASS)
                || hasConfiguredStylePermission(player, style));
    }

    private boolean hasPermissionUnlock(
            Player player,
            PermissionGroup group,
            NameStyle style
    ) {
        return group.unlockedStyles().contains(style.key()) || hasStylePermission(player, style);
    }

    private boolean hasConfiguredStylePermission(Player player, NameStyle style) {
        return style.permissions().stream().anyMatch(player::hasPermission);
    }

    @Override
    public Optional<StyleUnlockSource> unlockSourceCached(Player player, String styleKey) {
        String normalized = normalize(styleKey);
        NameStyle style = config.get().styles().get(normalized);
        if (style == null) {
            return Optional.empty();
        }
        PermissionGroup group = config.get().groupFor(player);
        boolean permissionUnlocked = hasPermissionUnlock(player, group, style);
        boolean grantUnlocked = cachedGrants(player.getUniqueId()).stream()
                .anyMatch(grant -> grant.styleKey().equals(normalized));
        return StyleUnlockSource.resolve(permissionUnlocked, grantUnlocked);
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        operations.close();
        IllegalStateException failure = new IllegalStateException("Colorful-name service is closed");
        loads.values().forEach(request -> request.future().completeExceptionally(failure));
        loads.clear();
        defaultEquips.values().forEach(result -> result.completeExceptionally(failure));
        defaultEquips.clear();
        defaultStyles.clear();
        cache.clear();
    }

    private CompletionStage<Boolean> loadSnapshot(PlayerCacheStore.Session session) {
        UUID playerId = session.playerId();
        return operations.submit(playerId, () -> repository.snapshot(playerId).thenApply(snapshot -> {
            if (closed.get() || !cache.isCurrent(session)) {
                return false;
            }
            String active = snapshot.activeStyleKey()
                    .filter(key -> config.get().styles().containsKey(key))
                    .orElse(null);
            List<NameGrant> grants = validGrants(snapshot.grants(), Instant.now());
            return cache.replaceDataIfChanged(session, grants, active);
        }));
    }

    private List<NameGrant> replaceGrant(List<NameGrant> existing, NameGrant grant) {
        List<NameGrant> updated = new ArrayList<>(existing);
        updated.removeIf(value -> value.styleKey().equals(grant.styleKey()));
        updated.add(grant);
        return List.copyOf(updated);
    }

    private String normalize(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    private String normalizeSource(String source) {
        return source == null || source.isBlank() ? "api" : source;
    }

    private void fireGlobal(Runnable event) {
        playerTasks.scheduler().runGlobal(event);
    }

    private void refreshPlaceholderIfOnline(UUID playerId) {
        playerTasks.scheduler().runForOnlinePlayer(playerId, this::refreshPlaceholderSnapshot, () -> { });
    }

    private void cancelPendingDefaultEquip(UUID playerId) {
        CompletableFuture<Boolean> pending = defaultEquips.remove(playerId);
        if (pending != null) {
            pending.complete(false);
        }
    }

    private List<NameGrant> validGrants(List<NameGrant> grants, Instant now) {
        return grants.stream().filter(grant -> grant.validAt(now)).toList();
    }

    private CompletionStage<Void> closedFuture() {
        return CompletableFuture.failedFuture(new IllegalStateException("Colorful-name service is closed"));
    }

    private record LoadRequest(PlayerCacheStore.Session session, CompletableFuture<Void> future) { }
}
