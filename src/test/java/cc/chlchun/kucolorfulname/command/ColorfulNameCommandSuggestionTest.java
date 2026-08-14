package cc.chlchun.kucolorfulname.command;

import cc.chlchun.kucolorfulname.config.ColorfulNameConfig;
import cc.chlchun.kucolorfulname.platform.OnlinePlayerIndex;
import cc.chlchun.kucolorfulname.platform.PlayerTasks;
import cc.chlchun.kucolorfulname.render.NameRenderer;
import cc.chlchun.kucolorfulname.service.ColorfulNameService;
import cc.chlchun.kucolorfulname.storage.NameRepository;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorfulNameCommandSuggestionTest {
    private static final Set<String> ADMIN_PERMISSIONS = Set.of(
            "kucolorfulname.admin.gui",
            "kucolorfulname.admin.grant",
            "kucolorfulname.admin.extend",
            "kucolorfulname.admin.revoke",
            "kucolorfulname.admin.audit",
            "kucolorfulname.admin.reload"
    );
    private ColorfulNameService service;
    private ColorfulNameCommand command;

    @BeforeEach
    void setUp() {
        ColorfulNameConfig config = config();
        Plugin plugin = plugin();
        service = new ColorfulNameService(
                repository(), () -> config, new NameRenderer(), plugin, new PlayerTasks(plugin));
        OnlinePlayerIndex onlinePlayers = new OnlinePlayerIndex();
        onlinePlayers.add(UUID.randomUUID(), "Zoe");
        onlinePlayers.add(UUID.randomUUID(), "alice");
        command = new ColorfulNameCommand(
                null, service, null, null, null, onlinePlayers, () -> null);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void emptyBasicCommandArgumentsShowRootSuggestionsImmediately() {
        assertEquals(
                List.of("off", "admin", "grant", "extend", "revoke", "audit", "reload"),
                command.suggest(playerWith(ADMIN_PERMISSIONS), new String[0])
        );
    }

    @Test
    void suggestionsFollowPermissionsPrefixesAndArgumentPositions() {
        Player admin = playerWith(ADMIN_PERMISSIONS);

        assertEquals(List.of("grant"), command.suggest(admin, new String[]{"g"}));
        assertEquals(List.of("alice", "Zoe"),
                command.suggest(admin, new String[]{"grant", ""}));
        assertEquals(List.of("aurora", "ruby"),
                command.suggest(admin, new String[]{"grant", "alice", ""}));
        assertEquals(List.of("30s", "5m", "30m", "1h", "12h", "1d", "7d", "30d", "4w"),
                command.suggest(admin, new String[]{"grant", "alice", "ruby", ""}));
        assertEquals(List.of("5", "10", "20", "50"),
                command.suggest(admin, new String[]{"audit", "alice", ""}));

        Player regularPlayer = playerWith(Set.of());
        assertEquals(List.of("off"), command.suggest(regularPlayer, new String[0]));
        assertEquals(List.of(), command.suggest(
                regularPlayer, new String[]{"grant", ""}));
    }

    private ColorfulNameConfig config() {
        YamlConfiguration configuration = new YamlConfiguration();
        addStyle(configuration, "ruby", "#FF0000");
        addStyle(configuration, "aurora", "#00FFFF");
        return ColorfulNameConfig.load(configuration);
    }

    private void addStyle(YamlConfiguration configuration, String key, String color) {
        configuration.set("styles." + key + ".display-name", key);
        configuration.set("styles." + key + ".type", "SOLID");
        configuration.set("styles." + key + ".colors", List.of(color));
        configuration.set("styles." + key + ".icon", "NAME_TAG");
    }

    private Player playerWith(Set<String> permissions) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> UUID.fromString("00000000-0000-0000-0000-000000000001");
                    case "getName" -> "SuggestionTester";
                    case "hasPermission" -> permissions.contains((String) arguments[0]);
                    case "isOnline" -> true;
                    case "toString" -> "SuggestionTester";
                    default -> primitiveDefault(method.getReturnType());
                }
        );
    }

    private NameRepository repository() {
        return (NameRepository) Proxy.newProxyInstance(
                NameRepository.class.getClassLoader(),
                new Class<?>[]{NameRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "ColorfulNameCommandSuggestionTest";
                    case "getLogger" -> Logger.getLogger("ColorfulNameCommandSuggestionTest");
                    case "isEnabled" -> true;
                    case "toString" -> "ColorfulNameCommandSuggestionTestPlugin";
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
