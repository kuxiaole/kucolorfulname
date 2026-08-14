package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.api.StyleType;
import cc.chlchun.kucolorfulname.config.ColorfulNameConfig;
import cc.chlchun.kucolorfulname.config.PermissionGroup;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColorfulNameConfigTest {
    @Test
    void loadsPriorityGroupsAndValidatedStyles() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("groups.default.priority", 0);
        configuration.set("groups.default.permission", "");
        configuration.set("groups.default.unlocked-styles", List.of("ruby"));
        configuration.set("groups.default.default-style", "");
        configuration.set("groups.vip.priority", 100);
        configuration.set("groups.vip.permission", "kucolorfulname.group.vip");
        configuration.set("groups.vip.unlocked-styles", List.of("RUBY"));
        configuration.set("groups.vip.default-style", "RUBY");
        configuration.set("styles.ruby.display-name", "Ruby");
        configuration.set("styles.ruby.type", "SOLID");
        configuration.set("styles.ruby.colors", List.of("#FF0000"));
        configuration.set("styles.ruby.decorations", List.of());
        configuration.set("styles.ruby.icon", "RED_DYE");
        configuration.set("styles.ruby.permission", "KuColorfulName.Style.Ruby");
        configuration.set("styles.ruby.permissions", List.of(
                "KuColorfulName.Style.Ruby.Alternative",
                "KUCOLORFULNAME.STYLE.RUBY"
        ));

        ColorfulNameConfig loaded = ColorfulNameConfig.load(configuration);
        assertEquals("ruby", loaded.styles().keySet().iterator().next());
        assertEquals("kucolorfulname.style.ruby", loaded.styles().get("ruby").permission());
        assertEquals(List.of(
                "kucolorfulname.style.ruby",
                "kucolorfulname.style.ruby.alternative"
        ), loaded.styles().get("ruby").permissions());
        assertEquals("vip", loaded.groups().getFirst().name());
        assertEquals(Set.of("ruby"), loaded.groups().getFirst().unlockedStyles());
        assertEquals("ruby", loaded.groups().getFirst().defaultStyle());
        PermissionGroup fallback = loaded.groups().getLast();
        assertEquals(Set.of("ruby"), fallback.unlockedStyles());
        assertEquals("", fallback.defaultStyle());
    }

    @Test
    void rejectsInvalidMaterialAndDuplicateFallbackGroups() {
        YamlConfiguration invalidStyle = new YamlConfiguration();
        invalidStyle.set("styles.ruby.type", "SOLID");
        invalidStyle.set("styles.ruby.colors", List.of("#FF0000"));
        invalidStyle.set("styles.ruby.icon", "NOT_A_MATERIAL");
        assertThrows(IllegalArgumentException.class, () -> ColorfulNameConfig.load(invalidStyle));

        YamlConfiguration duplicateFallback = new YamlConfiguration();
        duplicateFallback.set("groups.one.permission", "");
        duplicateFallback.set("groups.two.permission", "");
        assertThrows(IllegalArgumentException.class, () -> ColorfulNameConfig.load(duplicateFallback));
    }

    @Test
    void rejectsAnimationFieldsOnStaticStyles() {
        YamlConfiguration staticPeriod = configurationWithRubyStyle();
        staticPeriod.set("styles.ruby.animation-period-ticks", 4);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> ColorfulNameConfig.load(staticPeriod));
        assertTrue(error.getMessage().contains("require type ANIMATED"));
    }

    @Test
    void loadsServerAnimationWithAFullCyclePeriod() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("styles.wave.display-name", "Wave");
        configuration.set("styles.wave.type", "ANIMATED");
        configuration.set("styles.wave.colors", List.of("#00F5FF", "#FF00E6"));
        configuration.set("styles.wave.icon", "ECHO_SHARD");
        configuration.set("styles.wave.animation-mode", "FLOW");
        configuration.set("styles.wave.animation-period-ticks", 40);

        ColorfulNameConfig loaded = ColorfulNameConfig.load(configuration);

        var style = loaded.styles().get("wave");
        assertEquals(StyleType.ANIMATED, style.type());
        assertEquals("FLOW", style.animationMode().name());
        assertEquals(40, style.animationPeriodTicks());
    }

    @Test
    void rejectsUnknownAnimationMode() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("styles.wave.type", "ANIMATED");
        configuration.set("styles.wave.colors", List.of("#00F5FF", "#FF00E6"));
        configuration.set("styles.wave.icon", "ECHO_SHARD");
        configuration.set("styles.wave.animation-mode", "SPARKLE");
        configuration.set("styles.wave.animation-period-ticks", 40);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> ColorfulNameConfig.load(configuration));

        assertTrue(error.getMessage().contains("unknown animation-mode"));
    }

    @Test
    void rejectsUnknownGroupStylesAndLegacyGroupKeys() {
        YamlConfiguration unknownStyle = configurationWithRubyStyle();
        unknownStyle.set("groups.default.permission", "");
        unknownStyle.set("groups.default.unlocked-styles", List.of("missing"));
        IllegalArgumentException unknownError = assertThrows(
                IllegalArgumentException.class,
                () -> ColorfulNameConfig.load(unknownStyle)
        );
        assertTrue(unknownError.getMessage().contains("unknown style key"));

        YamlConfiguration legacyCapacity = configurationWithRubyStyle();
        legacyCapacity.set("groups.default.permission", "");
        legacyCapacity.set("groups.default.max-owned-styles", 5);
        IllegalArgumentException capacityError = assertThrows(
                IllegalArgumentException.class,
                () -> ColorfulNameConfig.load(legacyCapacity)
        );
        assertTrue(capacityError.getMessage().contains("max-owned-styles is no longer supported"));

        YamlConfiguration legacyTypes = configurationWithRubyStyle();
        legacyTypes.set("groups.default.permission", "");
        legacyTypes.set("groups.default.allowed-types", List.of("SOLID"));
        IllegalArgumentException typesError = assertThrows(
                IllegalArgumentException.class,
                () -> ColorfulNameConfig.load(legacyTypes)
        );
        assertTrue(typesError.getMessage().contains("allowed-types is no longer supported"));

        YamlConfiguration unknownDefault = configurationWithRubyStyle();
        unknownDefault.set("groups.default.permission", "");
        unknownDefault.set("groups.default.default-style", "missing");
        IllegalArgumentException defaultError = assertThrows(
                IllegalArgumentException.class,
                () -> ColorfulNameConfig.load(unknownDefault)
        );
        assertTrue(defaultError.getMessage().contains("unknown style key in default-style"));

        YamlConfiguration defaultNotUnlocked = configurationWithRubyStyle();
        defaultNotUnlocked.set("groups.default.permission", "");
        defaultNotUnlocked.set("groups.default.default-style", "ruby");
        IllegalArgumentException notUnlockedError = assertThrows(
                IllegalArgumentException.class,
                () -> ColorfulNameConfig.load(defaultNotUnlocked)
        );
        assertTrue(notUnlockedError.getMessage().contains("default-style must also be listed"));

        YamlConfiguration listDefault = configurationWithRubyStyle();
        listDefault.set("groups.vip.permission", "example.group.vip");
        listDefault.set("groups.vip.unlocked-styles", List.of("ruby"));
        listDefault.set("groups.vip.default-style", List.of("ruby", "amber"));
        IllegalArgumentException listError = assertThrows(
                IllegalArgumentException.class,
                () -> ColorfulNameConfig.load(listDefault)
        );
        assertTrue(listError.getMessage().contains("default-style must be one scalar"));

        YamlConfiguration fallbackDefault = configurationWithRubyStyle();
        fallbackDefault.set("groups.default.permission", "");
        fallbackDefault.set("groups.default.unlocked-styles", List.of("ruby"));
        fallbackDefault.set("groups.default.default-style", "ruby");
        IllegalArgumentException fallbackError = assertThrows(
                IllegalArgumentException.class,
                () -> ColorfulNameConfig.load(fallbackDefault)
        );
        assertTrue(fallbackError.getMessage().contains("fallback group cannot configure default-style"));
    }

    @Test
    void createsOneEmptyFallbackGroupWhenNoneIsConfigured() {
        YamlConfiguration configuration = configurationWithRubyStyle();
        configuration.set("groups.vip.permission", "kucolorfulname.group.vip");
        configuration.set("groups.vip.priority", 100);
        configuration.set("groups.vip.unlocked-styles", List.of("ruby"));

        ColorfulNameConfig loaded = ColorfulNameConfig.load(configuration);

        List<PermissionGroup> fallbacks = loaded.groups().stream()
                .filter(group -> group.permission().isBlank())
                .toList();
        assertEquals(1, fallbacks.size());
        assertEquals(Set.of(), fallbacks.getFirst().unlockedStyles());
        assertEquals("", fallbacks.getFirst().defaultStyle());
    }

    @Test
    void bundledPresetsHaveUniqueNonDefaultPermissions() throws Exception {
        YamlConfiguration defaults;
        try (var stream = Objects.requireNonNull(
                getClass().getResourceAsStream("/config.yml"), "missing bundled config.yml");
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        }
        ColorfulNameConfig loaded = ColorfulNameConfig.load(defaults);

        assertEquals(56, loaded.styles().size());
        assertEquals(1, loaded.groups().stream()
                .filter(group -> group.permission().isBlank()).count());
        assertTrue(loaded.groups().stream()
                .flatMap(group -> group.unlockedStyles().stream())
                .allMatch(loaded.styles()::containsKey));

        PermissionGroup defaultGroup = groupNamed(loaded, "default");
        PermissionGroup vipGroup = groupNamed(loaded, "vip");
        PermissionGroup svipGroup = groupNamed(loaded, "svip");
        PermissionGroup adminGroup = groupNamed(loaded, "admin");
        PermissionGroup animatedGroup = groupNamed(loaded, "animated");
        assertEquals(0, defaultGroup.unlockedStyles().size());
        assertEquals("", defaultGroup.defaultStyle());
        assertEquals(25, vipGroup.unlockedStyles().size());
        assertTrue(vipGroup.unlockedStyles().stream()
                .allMatch(key -> loaded.styles().get(key).type() == StyleType.SOLID));
        assertEquals("ruby", vipGroup.defaultStyle());
        assertEquals(40, svipGroup.unlockedStyles().size());
        assertEquals("kucolorfulname.group.svip", svipGroup.permission());
        assertEquals("aurora", svipGroup.defaultStyle());
        assertEquals(40, adminGroup.unlockedStyles().size());
        assertEquals("", adminGroup.defaultStyle());
        assertEquals(56, animatedGroup.unlockedStyles().size());
        assertEquals("kucolorfulname.group.animated", animatedGroup.permission());
        assertEquals(2000, animatedGroup.priority());
        assertEquals("", animatedGroup.defaultStyle());
        assertEquals(16, loaded.styles().values().stream()
                .filter(style -> style.type() == StyleType.ANIMATED).count());

        assertEquals(40, loaded.styles().values().stream()
                .flatMap(style -> style.permissions().stream()).distinct().count());

        YamlConfiguration descriptor;
        try (var stream = Objects.requireNonNull(
                getClass().getResourceAsStream("/paper-plugin.yml"), "missing paper-plugin.yml");
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            descriptor = YamlConfiguration.loadConfiguration(reader);
        }
        loaded.styles().values().stream().flatMap(style -> style.permissions().stream()).forEach(permission -> {
            var definition = descriptor.getConfigurationSection("permissions." + permission);
            assertTrue(definition != null, "undeclared permission: " + permission);
            assertFalse(definition.getBoolean("default", true),
                    "style permission must default to false: " + permission);
        });
        var svipDefinition = descriptor.getConfigurationSection(
                "permissions.kucolorfulname.group.svip");
        assertTrue(svipDefinition != null, "undeclared permission: kucolorfulname.group.svip");
        assertFalse(svipDefinition.getBoolean("default", true),
                "SVIP group permission must default to false");
        var animatedDefinition = descriptor.getConfigurationSection(
                "permissions.kucolorfulname.group.animated");
        assertTrue(animatedDefinition != null,
                "undeclared permission: kucolorfulname.group.animated");
        assertFalse(animatedDefinition.getBoolean("default", true),
                "animated group permission must default to false");
    }

    @Test
    void highestPriorityGroupOwnsTheAutomaticStyle() throws Exception {
        YamlConfiguration defaults;
        try (var stream = Objects.requireNonNull(
                getClass().getResourceAsStream("/config.yml"), "missing bundled config.yml");
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        }
        ColorfulNameConfig loaded = ColorfulNameConfig.load(defaults);

        PermissionGroup selected = loaded.groupFor(senderWith(
                "kucolorfulname.group.vip", "kucolorfulname.group.svip"));
        assertEquals("svip", selected.name());
        assertEquals("aurora", selected.defaultStyle());

        PermissionGroup admin = loaded.groupFor(senderWith("kucolorfulname.group.admin"));
        assertEquals("admin", admin.name());
        assertEquals(1000, admin.priority());

        PermissionGroup ordinary = loaded.groupFor(senderWith());
        assertEquals("default", ordinary.name());
        assertEquals("", ordinary.defaultStyle());
    }

    @Test
    void fallbackNeverShadowsAGroupTheSenderOwns() {
        YamlConfiguration configuration = configurationWithRubyStyle();
        configuration.set("groups.default.priority", 10_000);
        configuration.set("groups.default.permission", "");
        configuration.set("groups.vip.priority", 100);
        configuration.set("groups.vip.permission", "example.group.vip");
        configuration.set("groups.vip.unlocked-styles", List.of("ruby"));
        configuration.set("groups.vip.default-style", "ruby");

        ColorfulNameConfig loaded = ColorfulNameConfig.load(configuration);

        assertEquals("vip", loaded.groupFor(senderWith("example.group.vip")).name());
        assertEquals("default", loaded.groupFor(senderWith()).name());
    }

    private PermissionGroup groupNamed(ColorfulNameConfig config, String name) {
        return config.groups().stream()
                .filter(group -> group.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing group: " + name));
    }

    private CommandSender senderWith(String... permissions) {
        Set<String> granted = Set.of(permissions);
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hasPermission" -> granted.contains((String) arguments[0]);
                    case "toString" -> "ConfigTestSender";
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

    private YamlConfiguration configurationWithRubyStyle() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("styles.ruby.display-name", "Ruby");
        configuration.set("styles.ruby.type", "SOLID");
        configuration.set("styles.ruby.colors", List.of("#FF0000"));
        configuration.set("styles.ruby.decorations", List.of());
        configuration.set("styles.ruby.icon", "RED_DYE");
        return configuration;
    }
}
