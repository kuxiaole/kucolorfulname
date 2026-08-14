package cc.chlchun.kucolorfulname.service;

import cc.chlchun.kucolorfulname.api.ActivationResult;
import cc.chlchun.kucolorfulname.api.GrantAuditEntry;
import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.PlayerNameSnapshot;
import cc.chlchun.kucolorfulname.config.ColorfulNameConfig;
import cc.chlchun.kucolorfulname.platform.PlayerTasks;
import cc.chlchun.kucolorfulname.render.NameRenderer;
import cc.chlchun.kucolorfulname.storage.NameRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorfulNameServiceDefaultStyleTest {
    private static final String VIP_PERMISSION = "example.group.vip";
    private static final String RUBY = "ruby";
    private static final String AURORA = "aurora";
    private static final String MANUAL = "manual";
    private final UUID playerId = UUID.randomUUID();
    private final Player player = player(playerId, Set.of(VIP_PERMISSION));
    private Server previousServer;
    private ColorfulNameConfig rubyConfig;
    private ColorfulNameConfig auroraConfig;
    private ColorfulNameConfig noDefaultConfig;

    @BeforeEach
    void installServer() throws Exception {
        Field server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        previousServer = (Server) server.get(null);
        server.set(null, null);
        rubyConfig = config(RUBY);
        auroraConfig = config(AURORA);
        noDefaultConfig = config("");
        server.set(null, server(player));
    }

    @AfterEach
    void restoreServer() throws Exception {
        Field server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        server.set(null, previousServer);
    }

    @Test
    void manualOffSurvivesDatabaseRefreshWithANullActiveStyle() throws Exception {
        ControlledRepository repository = new ControlledRepository();
        AtomicReference<ColorfulNameConfig> config = new AtomicReference<>(rubyConfig);
        ColorfulNameService service = service(repository, config);
        service.load(playerId).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertTrue(service.ensureDefaultStyle(player).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(RUBY, repository.activeStyle.get());

        service.deactivate(playerId).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertNull(repository.activeStyle.get());
        service.refresh(playerId).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertFalse(service.ensureDefaultStyle(player).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertNull(repository.activeStyle.get());
        assertEquals(List.of(RUBY), repository.writtenStyles);
    }

    @Test
    void queuedDefaultCannotOverrideManualActivation() throws Exception {
        ControlledRepository repository = new ControlledRepository();
        AtomicReference<ColorfulNameConfig> config = new AtomicReference<>(rubyConfig);
        ColorfulNameService service = service(repository, config);
        service.load(playerId).toCompletableFuture().get(1, TimeUnit.SECONDS);
        CompletionStage<NameGrant> blocker = service.grant(
                playerId, RUBY, Duration.ofMinutes(1), playerId, "test-blocker");

        CompletionStage<Boolean> automatic = service.ensureDefaultStyle(player);
        CompletionStage<ActivationResult> manual = service.activate(player, MANUAL);
        repository.releaseBlockedSave();

        blocker.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertFalse(automatic.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(ActivationResult.ACTIVATED,
                manual.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(List.of(MANUAL), repository.writtenStyles);
        assertEquals(MANUAL, repository.activeStyle.get());
    }

    @Test
    void activationIntentBlocksDefaultsWhileItsSnapshotLoadIsPending() throws Exception {
        ControlledRepository repository = new ControlledRepository();
        repository.blockNextSnapshot();
        AtomicReference<ColorfulNameConfig> config = new AtomicReference<>(rubyConfig);
        ColorfulNameService service = service(repository, config);

        CompletionStage<ActivationResult> manual = service.activate(player, MANUAL);
        CompletionStage<Boolean> automatic = service.ensureDefaultStyle(player);
        assertFalse(automatic.toCompletableFuture().isDone());
        repository.releaseBlockedSnapshot(playerId);

        assertFalse(automatic.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(ActivationResult.ACTIVATED,
                manual.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(List.of(MANUAL), repository.writtenStyles);
        assertEquals(MANUAL, repository.activeStyle.get());
    }

    @Test
    void queuedDefaultCannotOverrideManualDeactivation() throws Exception {
        ControlledRepository repository = new ControlledRepository();
        AtomicReference<ColorfulNameConfig> config = new AtomicReference<>(rubyConfig);
        ColorfulNameService service = service(repository, config);
        service.load(playerId).toCompletableFuture().get(1, TimeUnit.SECONDS);
        CompletionStage<NameGrant> blocker = service.grant(
                playerId, RUBY, Duration.ofMinutes(1), playerId, "test-blocker");

        CompletionStage<Boolean> automatic = service.ensureDefaultStyle(player);
        CompletionStage<Void> manual = service.deactivate(playerId);
        repository.releaseBlockedSave();

        blocker.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertFalse(automatic.toCompletableFuture().get(1, TimeUnit.SECONDS));
        manual.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertTrue(repository.writtenStyles.isEmpty());
        assertEquals(1, repository.clearCalls.get());
        assertNull(repository.activeStyle.get());
    }

    @Test
    void reloadReplacesAnOldAutomaticStyleForTheSameGroupName() throws Exception {
        ControlledRepository repository = new ControlledRepository();
        AtomicReference<ColorfulNameConfig> config = new AtomicReference<>(rubyConfig);
        ColorfulNameService service = service(repository, config);
        service.load(playerId).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertTrue(service.ensureDefaultStyle(player).toCompletableFuture().get(1, TimeUnit.SECONDS));
        config.set(auroraConfig);
        service.configurationReloaded();

        assertTrue(service.ensureDefaultStyle(player).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(List.of(RUBY, AURORA), repository.writtenStyles);
        assertEquals(AURORA, repository.activeStyle.get());
    }

    @Test
    void reloadClearsAnAutomaticStyleWhenTheSameGroupRemovesItsDefault() throws Exception {
        ControlledRepository repository = new ControlledRepository();
        AtomicReference<ColorfulNameConfig> config = new AtomicReference<>(rubyConfig);
        ColorfulNameService service = service(repository, config);
        service.load(playerId).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertTrue(service.ensureDefaultStyle(player).toCompletableFuture().get(1, TimeUnit.SECONDS));
        config.set(noDefaultConfig);
        service.configurationReloaded();

        assertTrue(service.ensureDefaultStyle(player).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertNull(repository.activeStyle.get());
        assertEquals(1, repository.clearCalls.get());
    }

    private ColorfulNameService service(
            NameRepository repository,
            AtomicReference<ColorfulNameConfig> config
    ) {
        Plugin plugin = plugin();
        return new ColorfulNameService(
                repository, config::get, new NameRenderer(), plugin, new PlayerTasks(plugin));
    }

    private ColorfulNameConfig config(String defaultStyle) {
        Server installed = Bukkit.getServer();
        setBukkitServer(null);
        try {
            YamlConfiguration configuration = new YamlConfiguration();
            addStyle(configuration, RUBY, "#FF0000");
            addStyle(configuration, AURORA, "#00AAFF");
            addStyle(configuration, MANUAL, "#00FF00");
            configuration.set("groups.default.permission", "");
            configuration.set("groups.default.priority", 0);
            configuration.set("groups.default.unlocked-styles", List.of());
            configuration.set("groups.vip.permission", VIP_PERMISSION);
            configuration.set("groups.vip.priority", 100);
            configuration.set("groups.vip.unlocked-styles", List.of(RUBY, AURORA, MANUAL));
            configuration.set("groups.vip.default-style", defaultStyle);
            return ColorfulNameConfig.load(configuration);
        } finally {
            setBukkitServer(installed);
        }
    }

    private void setBukkitServer(Server value) {
        try {
            Field server = Bukkit.class.getDeclaredField("server");
            server.setAccessible(true);
            server.set(null, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void addStyle(YamlConfiguration configuration, String key, String color) {
        configuration.set("styles." + key + ".display-name", key);
        configuration.set("styles." + key + ".type", "SOLID");
        configuration.set("styles." + key + ".colors", List.of(color));
        configuration.set("styles." + key + ".icon", "NAME_TAG");
    }

    private Server server(Player onlinePlayer) {
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
                PluginManager.class.getClassLoader(),
                new Class<?>[]{PluginManager.class},
                (proxy, method, arguments) -> primitiveDefault(method.getReturnType()));
        return (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isPrimaryThread" -> true;
                    case "getPlayer" -> arguments != null
                            && arguments.length == 1
                            && playerId.equals(arguments[0]) ? onlinePlayer : null;
                    case "getPluginManager" -> pluginManager;
                    case "getLogger" -> Logger.getLogger("ColorfulNameServiceDefaultStyleTest");
                    case "toString" -> "ColorfulNameServiceDefaultStyleTestServer";
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private Player player(UUID id, Set<String> permissions) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> "DefaultTester";
                    case "hasPermission" -> permissions.contains((String) arguments[0]);
                    case "isOnline", "isValid" -> true;
                    case "toString" -> "DefaultTester";
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "ColorfulNameServiceDefaultStyleTest";
                    case "getLogger" -> Logger.getLogger("ColorfulNameServiceDefaultStyleTest");
                    case "isEnabled" -> true;
                    case "toString" -> "ColorfulNameServiceDefaultStyleTestPlugin";
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private static final class ControlledRepository implements NameRepository {
        private final AtomicReference<String> activeStyle = new AtomicReference<>();
        private final List<String> writtenStyles = new CopyOnWriteArrayList<>();
        private final AtomicInteger clearCalls = new AtomicInteger();
        private final AtomicReference<NameGrant> blockedGrant = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<NameGrant>> blockedSave =
                new AtomicReference<>();
        private final AtomicReference<CompletableFuture<PlayerNameSnapshot>> blockedSnapshot =
                new AtomicReference<>();

        @Override
        public CompletionStage<PlayerNameSnapshot> snapshot(UUID playerId) {
            CompletableFuture<PlayerNameSnapshot> blocked = blockedSnapshot.get();
            if (blocked != null) {
                return blocked;
            }
            return CompletableFuture.completedFuture(new PlayerNameSnapshot(
                    playerId, List.of(), Optional.ofNullable(activeStyle.get())));
        }

        void blockNextSnapshot() {
            if (!blockedSnapshot.compareAndSet(null, new CompletableFuture<>())) {
                throw new IllegalStateException("A snapshot is already blocked");
            }
        }

        void releaseBlockedSnapshot(UUID playerId) {
            CompletableFuture<PlayerNameSnapshot> result = blockedSnapshot.getAndSet(null);
            if (result == null) {
                throw new IllegalStateException("No snapshot is blocked");
            }
            result.complete(new PlayerNameSnapshot(
                    playerId, List.of(), Optional.ofNullable(activeStyle.get())));
        }

        @Override
        public CompletionStage<List<NameGrant>> grants(UUID playerId) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletionStage<NameGrant> saveGrant(NameGrant grant) {
            CompletableFuture<NameGrant> result = new CompletableFuture<>();
            if (!blockedGrant.compareAndSet(null, grant)
                    || !blockedSave.compareAndSet(null, result)) {
                throw new IllegalStateException("Only one blocked save is supported");
            }
            return result;
        }

        void releaseBlockedSave() {
            NameGrant grant = blockedGrant.getAndSet(null);
            CompletableFuture<NameGrant> result = blockedSave.getAndSet(null);
            if (grant == null || result == null) {
                throw new IllegalStateException("No save is blocked");
            }
            result.complete(grant);
        }

        @Override
        public CompletionStage<Void> setActiveStyle(
                UUID playerId, String styleKey, UUID actorId, String source) {
            writtenStyles.add(styleKey);
            activeStyle.set(styleKey);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> clearActiveStyle(
                UUID playerId, UUID actorId, String source) {
            clearCalls.incrementAndGet();
            activeStyle.set(null);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Optional<NameGrant>> extendGrant(
                UUID playerId, String styleKey, Duration duration, UUID actorId, String source) {
            return unsupported();
        }

        @Override
        public CompletionStage<Boolean> deleteGrant(
                UUID playerId, String styleKey, UUID actorId, String source) {
            return unsupported();
        }

        @Override
        public CompletionStage<Optional<String>> activeStyle(UUID playerId) {
            return CompletableFuture.completedFuture(Optional.ofNullable(activeStyle.get()));
        }

        @Override
        public CompletionStage<Integer> removeExpired(Instant now) {
            return unsupported();
        }

        @Override
        public CompletionStage<List<GrantAuditEntry>> audit(UUID playerId, int limit) {
            return unsupported();
        }

        @Override
        public void close() { }

        private <T> CompletionStage<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
