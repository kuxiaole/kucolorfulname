package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.config.LanguageBundle;
import cc.chlchun.kucolorfulname.config.MenuSettings;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditableResourcesTest {
    @Test
    void bundledMenusLoadWithExpectedNavigation() throws Exception {
        YamlConfiguration configuration = resource("/menus.yml");
        MenuSettings menus = MenuSettings.load(configuration);

        assertTrue(menus.categorizedPlayerRepository());
        assertEquals(27, menus.playerHome().size());
        assertTrue(menus.playerHome().styleSlots().isEmpty());
        assertEquals(11, menus.playerHome().button("unlocked").slot());
        assertEquals(Material.LIME_DYE, menus.playerHome().button("unlocked").material());
        assertEquals(15, menus.playerHome().button("locked").slot());
        assertEquals(Material.GRAY_DYE, menus.playerHome().button("locked").material());
        assertEquals(13, menus.playerHome().button("preview").slot());
        assertEquals(22, menus.playerHome().button("disable").slot());
        assertEquals(54, menus.player().size());
        assertEquals(45, menus.player().styleSlots().size());
        assertEquals(45, menus.player().button("disable").slot());
        assertEquals(Material.MILK_BUCKET, menus.player().button("disable").material());
        assertEquals(46, menus.player().button("home").slot());
        assertEquals(47, menus.player().button("preview").slot());
        assertEquals(Material.PLAYER_HEAD, menus.player().button("preview").material());
        assertTrue(menus.player().button("preview").lore().stream()
                .anyMatch(line -> line.contains("%nickname%")));
        assertTrue(menus.player().button("preview").lore().stream()
                .anyMatch(line -> line.contains("%style%")));
        assertTrue(menus.playerHome().title().contains("<gradient:"));
        assertTrue(menus.player().title().contains("<gradient:"));
        assertTrue(menus.playerLocked().title().contains("<gradient:"));
        assertTrue(menus.admin().title().contains("<gradient:"));
        assertEquals("✦ 玩家颜色仓库 ✦", PlainTextComponentSerializer.plainText().serialize(
                LanguageBundle.render(menus.playerHome().title())));
        assertEquals("✦ 已解锁颜色 ✦", PlainTextComponentSerializer.plainText().serialize(
                LanguageBundle.render(menus.player().title())));
        assertEquals("✦ 未解锁颜色 ✦", PlainTextComponentSerializer.plainText().serialize(
                LanguageBundle.render(menus.playerLocked().title())));
        assertEquals("✦ 昵称管理 · Alice", PlainTextComponentSerializer.plainText().serialize(
                LanguageBundle.render(menus.admin().title().replace("%player%", "Alice"))));
        assertEquals(46, menus.playerLocked().button("home").slot());
        assertEquals(47, menus.playerLocked().button("preview").slot());
        assertEquals(47, menus.admin().button("audit").slot());
        assertEquals(53, menus.admin().button("close").slot());
    }

    @Test
    void languageReplacesPlaceholdersAndParsesMiniMessage() throws Exception {
        LanguageBundle language = LanguageBundle.load(resource("/lang.yml"));
        String plain = PlainTextComponentSerializer.plainText().serialize(language.component(
                "command.grant-issued",
                Map.of("player", "Alice", "style", "aurora", "duration", "7d")
        ));

        assertEquals("已向 Alice 发放 aurora（7d）。", plain);
        assertEquals("<red>未知样式：%style%", language.raw("command.unknown-style"));
        assertEquals("纯色", language.raw("gui.type-solid"));
        assertEquals("渐变", language.raw("gui.type-gradient"));
        assertFalse(language.raw("gui.audit-entry").equals("gui.audit-entry"));
        assertFalse(language.raw("gui.unknown-style").equals("gui.unknown-style"));
    }

    @Test
    void rejectsMenuButtonOverlappingStyleSlots() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("player.size", 9);
        configuration.set("player.style-slots", List.of(0));
        configuration.set("player.buttons.close.slot", 0);
        configuration.set("player.buttons.close.material", "BARRIER");

        assertThrows(IllegalArgumentException.class, () -> MenuSettings.load(configuration));
    }

    @Test
    void rejectsEmptyItemOutsideMenuOrOverlappingButton() {
        YamlConfiguration outside = new YamlConfiguration();
        outside.set("player.size", 9);
        outside.set("player.empty.slot", 9);
        outside.set("player.empty.material", "GRAY_DYE");
        assertThrows(IllegalArgumentException.class, () -> MenuSettings.load(outside));

        YamlConfiguration overlap = new YamlConfiguration();
        overlap.set("player.size", 9);
        overlap.set("player.style-slots", List.of(0));
        overlap.set("player.buttons.close.slot", 8);
        overlap.set("player.buttons.close.material", "BARRIER");
        overlap.set("player.empty.slot", 8);
        overlap.set("player.empty.material", "GRAY_DYE");
        assertThrows(IllegalArgumentException.class, () -> MenuSettings.load(overlap));
    }

    @Test
    void supportsLegacyPlayerMenuAndRejectsPartialCategorizedConfiguration() {
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("player.size", 9);
        legacy.set("player.style-slots", List.of(0));
        MenuSettings legacyMenus = MenuSettings.load(legacy);

        assertFalse(legacyMenus.categorizedPlayerRepository());
        assertEquals(legacyMenus.player(), legacyMenus.playerHome());
        assertEquals(legacyMenus.player(), legacyMenus.playerLocked());

        YamlConfiguration partial = new YamlConfiguration();
        partial.set("player-home.size", 27);
        partial.set("player-home.style-slots", List.of());
        assertThrows(IllegalArgumentException.class, () -> MenuSettings.load(partial));
    }

    @Test
    void rejectsCategorizedMenusWithoutReachableViewsOrStyleSlots() {
        YamlConfiguration missingHomeButton = categorizedMenuConfiguration();
        missingHomeButton.set("player.buttons.home", null);
        assertThrows(IllegalArgumentException.class,
                () -> MenuSettings.load(missingHomeButton));

        YamlConfiguration emptyLockedView = categorizedMenuConfiguration();
        emptyLockedView.set("player-locked.style-slots", List.of());
        assertThrows(IllegalArgumentException.class,
                () -> MenuSettings.load(emptyLockedView));

        YamlConfiguration homeWithStyleSlots = categorizedMenuConfiguration();
        homeWithStyleSlots.set("player-home.style-slots", List.of(0));
        assertThrows(IllegalArgumentException.class,
                () -> MenuSettings.load(homeWithStyleSlots));
    }

    private YamlConfiguration categorizedMenuConfiguration() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("player-home.size", 9);
        configuration.set("player-home.style-slots", List.of());
        configuration.set("player-home.buttons.unlocked.slot", 1);
        configuration.set("player-home.buttons.unlocked.material", "LIME_DYE");
        configuration.set("player-home.buttons.locked.slot", 2);
        configuration.set("player-home.buttons.locked.material", "GRAY_DYE");
        configuration.set("player.size", 9);
        configuration.set("player.style-slots", List.of(0));
        configuration.set("player.buttons.home.slot", 8);
        configuration.set("player.buttons.home.material", "COMPASS");
        configuration.set("player-locked.size", 9);
        configuration.set("player-locked.style-slots", List.of(0));
        configuration.set("player-locked.buttons.home.slot", 8);
        configuration.set("player-locked.buttons.home.material", "COMPASS");
        return configuration;
    }

    private YamlConfiguration resource(String path) throws Exception {
        try (var stream = Objects.requireNonNull(
                getClass().getResourceAsStream(path), "missing resource " + path);
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
