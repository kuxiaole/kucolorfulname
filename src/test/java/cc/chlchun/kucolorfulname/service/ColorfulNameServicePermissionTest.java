package cc.chlchun.kucolorfulname.service;

import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.PlaceholderSnapshot;
import cc.chlchun.kucolorfulname.api.PlaceholderStatus;
import cc.chlchun.kucolorfulname.api.PlayerNameSnapshot;
import cc.chlchun.kucolorfulname.api.StyleUnlockSource;
import cc.chlchun.kucolorfulname.config.ColorfulNameConfig;
import cc.chlchun.kucolorfulname.platform.PlayerTasks;
import cc.chlchun.kucolorfulname.render.NameRenderer;
import cc.chlchun.kucolorfulname.storage.NameRepository;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorfulNameServicePermissionTest {
    private static final String PRIMARY = "example.color.primary";
    private static final String ALTERNATIVE = "example.color.alternative";
    private static final String VIP_PERMISSION = "example.group.vip";
    private static final String SVIP_PERMISSION = "example.group.svip";
    private static final String BYPASS = "kucolorfulname.admin.style-permission-bypass";
    private static final String VIP_STYLE = "vip-solid";
    private static final String SVIP_STYLE = "svip-gradient";
    private static final String GRANT_STYLE = "grant-only";
    private static final List<String> EXTRA_GRANT_STYLES = List.of(
            "grant-1", "grant-2", "grant-3", "grant-4", "grant-5", "grant-6");

    @Test
    void anyConfiguredPermissionOrBypassUnlocksTheStyle() {
        ColorfulNameService service = service();

        assertTrue(service.hasStylePermission(playerWith(PRIMARY), "ruby"));
        assertTrue(service.hasStylePermission(playerWith(ALTERNATIVE), "RUBY"));
        assertFalse(service.hasStylePermission(playerWith("example.color.other"), "ruby"));
        assertTrue(service.hasStylePermission(playerWith(BYPASS), "ruby"));
        assertFalse(service.hasStylePermission(playerWith(BYPASS), GRANT_STYLE));
    }

    @Test
    void groupsUnlockTheirBundledSolidAndGradientStyles() {
        ColorfulNameService service = service();
        Player vip = playerWith(VIP_PERMISSION);
        Player svip = playerWith(SVIP_PERMISSION);
        Player defaultPlayer = playerWith();

        assertEquals(Optional.of(StyleUnlockSource.PERMISSION),
                service.unlockSourceCached(vip, VIP_STYLE));
        assertEquals(Optional.of(StyleUnlockSource.PERMISSION),
                service.unlockSourceCached(svip, SVIP_STYLE));
        assertEquals(Optional.empty(), service.unlockSourceCached(defaultPlayer, VIP_STYLE));
        assertEquals(Optional.empty(), service.unlockSourceCached(defaultPlayer, SVIP_STYLE));

        assertFalse(service.hasStylePermission(vip, VIP_STYLE),
                "Group bundles must not masquerade as per-style permissions");
        assertFalse(service.hasStylePermission(svip, SVIP_STYLE),
                "Group bundles must not masquerade as per-style permissions");
    }

    @Test
    void perStylePermissionRemainsIndependentOfGroupBundles() {
        UUID playerId = UUID.randomUUID();
        Player player = playerWith(playerId, new HashSet<>(Set.of(PRIMARY)));
        ColorfulNameService service = service(List.of(), Optional.of("ruby"));

        service.load(playerId).toCompletableFuture().join();
        PlaceholderSnapshot snapshot = service.refreshPlaceholderSnapshot(player);

        assertEquals(Optional.of(StyleUnlockSource.PERMISSION),
                service.unlockSourceCached(player, "ruby"));
        assertEquals(Optional.of("ruby"), snapshot.activeStyleKey());
        assertNotEquals(Component.text("Fallback"), service.renderActive(playerId, "Fallback"));
    }

    @Test
    void validGrantRemainsIndependentOfGroupBundles() {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.now();
        NameGrant grant = new NameGrant(
                UUID.randomUUID(), playerId, GRANT_STYLE,
                now.minusSeconds(60), now.plusSeconds(3600), null, "test");
        ColorfulNameService service = service(List.of(grant), Optional.of(GRANT_STYLE));
        Player player = playerWith(playerId, new HashSet<>());

        service.load(playerId).toCompletableFuture().join();
        PlaceholderSnapshot snapshot = service.refreshPlaceholderSnapshot(player);

        assertEquals(Optional.of(StyleUnlockSource.GRANT),
                service.unlockSourceCached(player, GRANT_STYLE));
        assertTrue(snapshot.style(GRANT_STYLE).orElseThrow().grantUnlocked());
        assertEquals(Optional.of(GRANT_STYLE), snapshot.activeStyleKey());
    }

    @Test
    void allValidGrantsRemainUsableWithoutACapacityLimit() {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.now();
        List<NameGrant> grants = new ArrayList<>();
        for (String styleKey : EXTRA_GRANT_STYLES) {
            grants.add(new NameGrant(
                    UUID.randomUUID(), playerId, styleKey,
                    now.minusSeconds(60), now.plusSeconds(3600), null, "test"));
        }
        String selectedStyle = EXTRA_GRANT_STYLES.getLast();
        ColorfulNameService service = service(grants, Optional.of(selectedStyle));
        Player player = playerWith(playerId, new HashSet<>());

        service.load(playerId).toCompletableFuture().join();
        PlaceholderSnapshot snapshot = service.refreshPlaceholderSnapshot(player);

        assertEquals(EXTRA_GRANT_STYLES.size(), snapshot.ownedGrantCount());
        for (String styleKey : EXTRA_GRANT_STYLES) {
            assertTrue(snapshot.style(styleKey).orElseThrow().grantUnlocked(), styleKey);
            assertEquals(Optional.of(StyleUnlockSource.GRANT),
                    service.unlockSourceCached(player, styleKey), styleKey);
        }
        assertEquals(Optional.of(selectedStyle), snapshot.activeStyleKey());
    }

    @Test
    void refreshedGroupSnapshotControlsUuidRenderingAfterPermissionRemoval() {
        UUID playerId = UUID.randomUUID();
        Set<String> permissions = new HashSet<>(Set.of(VIP_PERMISSION));
        Player player = playerWith(playerId, permissions);
        ColorfulNameService service = service(List.of(), Optional.of(VIP_STYLE));

        service.load(playerId).toCompletableFuture().join();
        PlaceholderSnapshot unlocked = service.refreshPlaceholderSnapshot(player);

        assertEquals(Optional.of(VIP_STYLE), unlocked.activeStyleKey());
        assertNotEquals(Component.text("Fallback"), service.renderActive(playerId, "Fallback"));

        permissions.clear();
        PlaceholderSnapshot locked = service.refreshPlaceholderSnapshot(player);

        assertEquals(PlaceholderStatus.LOCKED, locked.status());
        assertEquals(Optional.empty(), locked.activeStyleKey());
        assertEquals(Component.text("Fallback"), service.renderActive(playerId, "Fallback"));
    }

    private ColorfulNameService service() {
        return service(List.of(), Optional.empty());
    }

    private ColorfulNameService service(List<NameGrant> grants, Optional<String> activeStyle) {
        YamlConfiguration configuration = new YamlConfiguration();
        addStyle(configuration, "ruby", "SOLID", List.of("#FF0000"),
                List.of(PRIMARY, ALTERNATIVE));
        addStyle(configuration, VIP_STYLE, "SOLID", List.of("#00AA00"), List.of());
        addStyle(configuration, SVIP_STYLE, "GRADIENT",
                List.of("#0066FF", "#FF00AA"), List.of());
        addStyle(configuration, GRANT_STYLE, "SOLID", List.of("#FFAA00"), List.of());
        for (String styleKey : EXTRA_GRANT_STYLES) {
            addStyle(configuration, styleKey, "SOLID", List.of("#BBBBBB"), List.of());
        }
        configuration.set("groups.default.permission", "");
        configuration.set("groups.default.priority", 0);
        configuration.set("groups.default.unlocked-styles", List.of());
        configuration.set("groups.vip.permission", VIP_PERMISSION);
        configuration.set("groups.vip.priority", 100);
        configuration.set("groups.vip.unlocked-styles", List.of(VIP_STYLE));
        configuration.set("groups.svip.permission", SVIP_PERMISSION);
        configuration.set("groups.svip.priority", 200);
        configuration.set("groups.svip.unlocked-styles", List.of(SVIP_STYLE));

        ColorfulNameConfig config = ColorfulNameConfig.load(configuration);
        Plugin plugin = plugin();
        NameRepository repository = repository(grants, activeStyle);
        return new ColorfulNameService(
                repository, () -> config, new NameRenderer(), plugin, new PlayerTasks(plugin));
    }

    private void addStyle(
            YamlConfiguration configuration,
            String key,
            String type,
            List<String> colors,
            List<String> permissions
    ) {
        configuration.set("styles." + key + ".display-name", key);
        configuration.set("styles." + key + ".type", type);
        configuration.set("styles." + key + ".colors", colors);
        configuration.set("styles." + key + ".icon", "NAME_TAG");
        configuration.set("styles." + key + ".permissions", permissions);
    }

    private NameRepository repository(List<NameGrant> grants, Optional<String> activeStyle) {
        return (NameRepository) Proxy.newProxyInstance(
                NameRepository.class.getClassLoader(),
                new Class<?>[]{NameRepository.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "snapshot" -> CompletableFuture.completedFuture(new PlayerNameSnapshot(
                            (UUID) arguments[0], grants, activeStyle));
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private Player playerWith(String... permissions) {
        return playerWith(UUID.randomUUID(), new HashSet<>(Set.of(permissions)));
    }

    private Player playerWith(UUID playerId, Set<String> permissions) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getName" -> "PermissionTester";
                    case "hasPermission" -> permissions.contains((String) arguments[0]);
                    case "isOnline" -> true;
                    case "toString" -> "PermissionTester";
                    default -> primitiveDefault(method.getReturnType());
                }
        );
    }

    private Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "ColorfulNameServicePermissionTest";
                    case "getLogger" -> Logger.getLogger("ColorfulNameServicePermissionTest");
                    case "isEnabled" -> true;
                    case "toString" -> "ColorfulNameServicePermissionTestPlugin";
                    default -> primitiveDefault(method.getReturnType());
                }
        );
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
}
