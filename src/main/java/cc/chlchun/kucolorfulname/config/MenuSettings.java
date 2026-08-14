package cc.chlchun.kucolorfulname.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable, validated representation of the editable menus.yml file. */
public final class MenuSettings {
    private final View playerHome;
    private final View player;
    private final View playerLocked;
    private final View admin;
    private final boolean categorizedPlayerRepository;

    private MenuSettings(
            View playerHome,
            View player,
            View playerLocked,
            View admin,
            boolean categorizedPlayerRepository
    ) {
        this.playerHome = playerHome;
        this.player = player;
        this.playerLocked = playerLocked;
        this.admin = admin;
        this.categorizedPlayerRepository = categorizedPlayerRepository;
    }

    public static MenuSettings load(FileConfiguration configuration) {
        ConfigurationSection playerSection = configuration.getConfigurationSection("player");
        ConfigurationSection playerHomeSection = configuration.getConfigurationSection("player-home");
        ConfigurationSection playerLockedSection = configuration.getConfigurationSection("player-locked");
        boolean hasPlayerHome = playerHomeSection != null;
        boolean hasPlayerLocked = playerLockedSection != null;
        if (hasPlayerHome != hasPlayerLocked) {
            throw new IllegalArgumentException(
                    "player-home and player-locked must either both be configured or both be absent");
        }

        View player = loadView(playerSection, false);
        View admin = loadView(configuration.getConfigurationSection("admin"), true);
        validateStyleView(player, "player");
        boolean categorized = hasPlayerHome;
        View playerHome = categorized ? loadView(playerHomeSection, false) : player;
        View playerLocked = categorized ? loadView(playerLockedSection, false) : player;
        if (categorized) {
            validateCategorizedPlayerViews(playerHome, player, playerLocked);
        }
        validateStyleView(admin, "admin");
        return new MenuSettings(
                playerHome,
                player,
                playerLocked,
                admin,
                categorized
        );
    }

    public View playerHome() {
        return playerHome;
    }

    public View player() {
        return player;
    }

    public View playerLocked() {
        return playerLocked;
    }

    public View admin() {
        return admin;
    }

    public boolean categorizedPlayerRepository() {
        return categorizedPlayerRepository;
    }

    private static View loadView(ConfigurationSection section, boolean admin) {
        int size = section == null ? 54 : section.getInt("size", 54);
        if (size < 9 || size > 54 || size % 9 != 0) {
            throw new IllegalArgumentException("menu size must be a multiple of 9 between 9 and 54");
        }
        List<Integer> defaultSlots = new ArrayList<>();
        for (int slot = 0; slot < Math.min(45, size); slot++) {
            defaultSlots.add(slot);
        }
        List<Integer> styleSlots = section == null || !section.contains("style-slots")
                ? defaultSlots
                : section.getIntegerList("style-slots");
        validateSlots(styleSlots, size, "style-slots");

        Map<String, Button> buttons = new LinkedHashMap<>();
        ConfigurationSection buttonSection = section == null
                ? null : section.getConfigurationSection("buttons");
        if (buttonSection != null) {
            for (String key : buttonSection.getKeys(false)) {
                ConfigurationSection definition = buttonSection.getConfigurationSection(key);
                if (definition != null) {
                    buttons.put(key.toLowerCase(Locale.ROOT), loadButton(definition, key));
                }
            }
        }
        Map<String, String> texts = new LinkedHashMap<>();
        ConfigurationSection textSection = section == null
                ? null : section.getConfigurationSection("texts");
        if (textSection != null) {
            textSection.getValues(true).forEach((key, value) -> {
                if (value instanceof String string) {
                    texts.put(key, string);
                }
            });
        }
        Button empty = section == null ? null : loadOptionalButton(section.getConfigurationSection("empty"), "empty");
        validateButtonSlots(buttons, styleSlots, size);
        validateEmptySlot(empty, buttons, size);
        String title = section == null ? (admin ? "<gold>管理颜色昵称：%player%" : "<aqua>颜色昵称仓库")
                : section.getString("title", admin
                ? "<gold>管理颜色昵称：%player%" : "<aqua>颜色昵称仓库");
        return new View(title, size, styleSlots, buttons, empty, texts);
    }

    private static Button loadOptionalButton(ConfigurationSection section, String key) {
        return section == null ? null : loadButton(section, key);
    }

    private static Button loadButton(ConfigurationSection section, String key) {
        int slot = section.getInt("slot", -1);
        String materialName = section.getString("material", "PAPER").trim().toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(materialName);
        if (material == null || (Bukkit.getServer() != null && !material.isItem())) {
            throw new IllegalArgumentException("menu button '" + key + "' has invalid material: " + materialName);
        }
        String name = section.getString("name", key);
        List<String> lore = List.copyOf(section.getStringList("lore"));
        return new Button(slot, material, name, lore);
    }

    private static void validateSlots(List<Integer> slots, int size, String label) {
        if (slots.stream().anyMatch(slot -> slot == null || slot < 0 || slot >= size)) {
            throw new IllegalArgumentException(label + " contains a slot outside the menu size");
        }
        if (slots.size() != slots.stream().distinct().count()) {
            throw new IllegalArgumentException(label + " contains duplicate slots");
        }
    }

    private static void validateButtonSlots(Map<String, Button> buttons, List<Integer> styleSlots, int size) {
        List<Integer> occupied = new ArrayList<>(styleSlots);
        for (Map.Entry<String, Button> entry : buttons.entrySet()) {
            Button button = entry.getValue();
            if (button.slot() < 0) {
                continue;
            }
            validateSlots(List.of(button.slot()), size, "button '" + entry.getKey() + "'");
            if (occupied.contains(button.slot())) {
                throw new IllegalArgumentException("menu button '" + entry.getKey()
                        + "' overlaps another menu slot");
            }
            occupied.add(button.slot());
        }
    }

    private static void validateEmptySlot(Button empty, Map<String, Button> buttons, int size) {
        if (empty == null || empty.slot() < 0) {
            return;
        }
        validateSlots(List.of(empty.slot()), size, "empty item");
        if (buttons.values().stream().anyMatch(button -> button.slot() == empty.slot())) {
            throw new IllegalArgumentException("empty item overlaps a menu button");
        }
    }

    private static void validateCategorizedPlayerViews(
            View playerHome,
            View player,
            View playerLocked
    ) {
        if (!playerHome.styleSlots().isEmpty()) {
            throw new IllegalArgumentException("player-home style-slots must be explicitly empty");
        }
        validateRequiredButton(playerHome, "player-home", "unlocked");
        validateRequiredButton(playerHome, "player-home", "locked");
        validateStyleView(playerLocked, "player-locked");
        validateRequiredButton(player, "player", "home");
        validateRequiredButton(playerLocked, "player-locked", "home");
    }

    private static void validateStyleView(View view, String label) {
        if (view.styleSlots().isEmpty()) {
            throw new IllegalArgumentException(label + " style-slots must contain at least one slot");
        }
    }

    private static void validateRequiredButton(View view, String viewName, String buttonName) {
        if (view.button(buttonName).slot() < 0) {
            throw new IllegalArgumentException(
                    viewName + " must configure the '" + buttonName + "' button");
        }
    }

    public record Button(int slot, Material material, String name, List<String> lore) {
        public Button {
            Objects.requireNonNull(material, "material");
            name = name == null ? "" : name;
            lore = List.copyOf(lore == null ? List.of() : lore);
        }
    }

    public static final class View {
        private final String title;
        private final int size;
        private final List<Integer> styleSlots;
        private final Map<String, Button> buttons;
        private final Button empty;
        private final Map<String, String> texts;

        private View(
                String title,
                int size,
                List<Integer> styleSlots,
                Map<String, Button> buttons,
                Button empty,
                Map<String, String> texts
        ) {
            this.title = title;
            this.size = size;
            this.styleSlots = List.copyOf(styleSlots);
            this.buttons = Collections.unmodifiableMap(new LinkedHashMap<>(buttons));
            this.empty = empty;
            this.texts = Collections.unmodifiableMap(new LinkedHashMap<>(texts));
        }

        public String title() {
            return title;
        }

        public int size() {
            return size;
        }

        public List<Integer> styleSlots() {
            return styleSlots;
        }

        public Button button(String key) {
            return buttons.getOrDefault(key.toLowerCase(Locale.ROOT),
                    new Button(-1, Material.PAPER, key, List.of()));
        }

        public Button empty() {
            return empty;
        }

        public String text(String key, String fallback) {
            return texts.getOrDefault(key, fallback);
        }
    }
}
