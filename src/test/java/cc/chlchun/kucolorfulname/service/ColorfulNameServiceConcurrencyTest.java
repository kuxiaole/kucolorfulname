package cc.chlchun.kucolorfulname.service;

import cc.chlchun.kucolorfulname.api.GrantAuditEntry;
import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.PlayerNameSnapshot;
import cc.chlchun.kucolorfulname.config.ColorfulNameConfig;
import cc.chlchun.kucolorfulname.platform.PlayerTasks;
import cc.chlchun.kucolorfulname.render.NameRenderer;
import cc.chlchun.kucolorfulname.storage.NameRepository;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorfulNameServiceConcurrencyTest {
    @Test
    void concurrentLoadsShareOneRepositorySnapshot() throws Exception {
        ControlledRepository repository = new ControlledRepository();
        ColorfulNameService service = service(repository);
        UUID playerId = UUID.randomUUID();

        CompletionStage<Void> first = service.load(playerId);
        CompletionStage<Void> second = service.load(playerId);

        assertSame(first, second);
        assertEquals(1, repository.snapshotCalls.get());
        repository.snapshotFuture.get().complete(snapshot(playerId, "ruby"));
        first.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertTrue(service.isCached(playerId));
        assertEquals("ruby", service.cachedGrants(playerId).getFirst().styleKey());
    }

    @Test
    void unloadDuringLoadPreventsStaleCachePublication() throws Exception {
        ControlledRepository repository = new ControlledRepository();
        ColorfulNameService service = service(repository);
        UUID playerId = UUID.randomUUID();

        CompletionStage<Void> load = service.load(playerId);
        service.unload(playerId);
        repository.snapshotFuture.get().complete(snapshot(playerId, "ruby"));
        load.toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertFalse(service.isCached(playerId));
        assertTrue(service.cachedGrants(playerId).isEmpty());
    }

    @Test
    void closeCompletesInflightLoadExceptionallyAndBlocksPublication() {
        ControlledRepository repository = new ControlledRepository();
        ColorfulNameService service = service(repository);
        UUID playerId = UUID.randomUUID();

        CompletableFuture<Void> load = service.load(playerId).toCompletableFuture();
        service.close();
        repository.snapshotFuture.get().complete(snapshot(playerId, "ruby"));

        assertThrows(ExecutionException.class, () -> load.get(1, TimeUnit.SECONDS));
        assertFalse(service.isCached(playerId));
    }

    @Test
    void synchronizeReportsOnlyDatabaseSnapshotChanges() throws Exception {
        ControlledRepository repository = new ControlledRepository();
        ColorfulNameService service = service(repository);
        UUID playerId = UUID.randomUUID();
        PlayerNameSnapshot initial = snapshot(playerId, "ruby");

        CompletionStage<Void> load = service.load(playerId);
        repository.snapshotFuture.get().complete(initial);
        load.toCompletableFuture().get(1, TimeUnit.SECONDS);

        CompletionStage<Boolean> unchanged = service.synchronize(playerId);
        repository.snapshotFuture.get().complete(initial);
        assertFalse(unchanged.toCompletableFuture().get(1, TimeUnit.SECONDS));

        PlayerNameSnapshot changed = new PlayerNameSnapshot(playerId, List.of(), Optional.empty());
        CompletionStage<Boolean> update = service.synchronize(playerId);
        repository.snapshotFuture.get().complete(changed);
        assertTrue(update.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertTrue(service.cachedGrants(playerId).isEmpty());
        assertEquals(Optional.empty(), service.cachedActiveStyleKey(playerId));
        assertEquals(3, repository.snapshotCalls.get());
    }

    private ColorfulNameService service(NameRepository repository) {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "ColorfulNameServiceTest";
                    case "getLogger" -> Logger.getLogger("ColorfulNameServiceTest");
                    case "isEnabled" -> true;
                    case "toString" -> "ColorfulNameServiceTestPlugin";
                    default -> primitiveDefault(method.getReturnType());
                }
        );
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("styles.ruby.display-name", "Ruby");
        configuration.set("styles.ruby.type", "SOLID");
        configuration.set("styles.ruby.colors", List.of("#FF0000"));
        configuration.set("styles.ruby.icon", "RED_DYE");
        ColorfulNameConfig config = ColorfulNameConfig.load(configuration);
        return new ColorfulNameService(
                repository, () -> config, new NameRenderer(), plugin, new PlayerTasks(plugin));
    }

    private PlayerNameSnapshot snapshot(UUID playerId, String styleKey) {
        Instant now = Instant.now();
        NameGrant grant = new NameGrant(
                UUID.randomUUID(), playerId, styleKey, now, now.plusSeconds(60), null, "test");
        return new PlayerNameSnapshot(playerId, List.of(grant), Optional.of(styleKey));
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
        private final AtomicInteger snapshotCalls = new AtomicInteger();
        private final AtomicReference<CompletableFuture<PlayerNameSnapshot>> snapshotFuture =
                new AtomicReference<>();

        @Override
        public CompletionStage<PlayerNameSnapshot> snapshot(UUID playerId) {
            snapshotCalls.incrementAndGet();
            CompletableFuture<PlayerNameSnapshot> future = new CompletableFuture<>();
            snapshotFuture.set(future);
            return future;
        }

        @Override
        public CompletionStage<List<NameGrant>> grants(UUID playerId) {
            return unsupported();
        }

        @Override
        public CompletionStage<NameGrant> saveGrant(NameGrant grant) {
            return unsupported();
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
            return unsupported();
        }

        @Override
        public CompletionStage<Void> setActiveStyle(
                UUID playerId, String styleKey, UUID actorId, String source) {
            return unsupported();
        }

        @Override
        public CompletionStage<Void> clearActiveStyle(UUID playerId, UUID actorId, String source) {
            return unsupported();
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
