package cc.chlchun.kucolorfulname.config;

import cc.chlchun.kucolorfulname.api.AnimationMode;
import cc.chlchun.kucolorfulname.api.NameStyle;
import cc.chlchun.kucolorfulname.api.StyleType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class ColorfulNameConfig {
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Set<String> MATERIAL_NAMES = Arrays.stream(Material.class.getDeclaredFields())
            .filter(field -> field.isEnumConstant())
            .map(field -> field.getName())
            .collect(Collectors.toUnmodifiableSet());
    private final PluginSettings settings;
    private final StorageSettings storage;
    private final Map<String, NameStyle> styles;
    private final List<PermissionGroup> groups;

    private ColorfulNameConfig(
            PluginSettings settings,
            StorageSettings storage,
            Map<String, NameStyle> styles,
            List<PermissionGroup> groups
    ) {
        this.settings = settings;
        this.storage = storage;
        this.styles = Collections.unmodifiableMap(new LinkedHashMap<>(styles));
        this.groups = List.copyOf(groups);
    }

    public static ColorfulNameConfig load(FileConfiguration config) {
        Map<String, NameStyle> styles = loadStyles(config);
        List<PermissionGroup> groups = loadGroups(config, styles.keySet());
        if (groups.stream().noneMatch(group -> group.permission().isBlank())) {
            groups.add(new PermissionGroup("default", "", 0, Set.of(), ""));
        }
        groups.sort(Comparator.comparingInt(PermissionGroup::priority).reversed());
        long fallbackGroups = groups.stream().filter(group -> group.permission().isBlank()).count();
        if (fallbackGroups != 1) {
            throw new IllegalArgumentException("Exactly one fallback group with an empty permission is required");
        }
        PermissionGroup fallback = groups.stream()
                .filter(group -> group.permission().isBlank())
                .findFirst()
                .orElseThrow();
        if (!fallback.defaultStyle().isBlank()) {
            throw new IllegalArgumentException(
                    "The fallback group cannot configure default-style; ordinary players must not auto-equip a style");
        }
        return new ColorfulNameConfig(
                PluginSettings.load(config), StorageSettings.load(config), styles, groups);
    }

    private static Map<String, NameStyle> loadStyles(FileConfiguration config) {
        Map<String, NameStyle> styles = new LinkedHashMap<>();
        ConfigurationSection root = config.getConfigurationSection("styles");
        if (root == null) {
            return styles;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            try {
                styles.put(key.toLowerCase(Locale.ROOT), parseStyle(key, section));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid style '" + key + "': " + exception.getMessage(), exception);
            }
        }
        return styles;
    }

    private static NameStyle parseStyle(String key, ConfigurationSection section) {
        StyleType type = StyleType.valueOf(section.getString("type", "SOLID").toUpperCase(Locale.ROOT));
        List<Integer> colors = new ArrayList<>();
        for (String raw : section.getStringList("colors")) {
            if (!HEX.matcher(raw).matches()) {
                throw new IllegalArgumentException("color must use #RRGGBB: " + raw);
            }
            TextColor color = TextColor.fromHexString(raw);
            if (color == null) {
                throw new IllegalArgumentException("invalid color: " + raw);
            }
            colors.add(color.value());
        }
        Set<TextDecoration> decorations = EnumSet.noneOf(TextDecoration.class);
        for (String raw : section.getStringList("decorations")) {
            try {
                decorations.add(TextDecoration.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unknown decoration: " + raw, exception);
            }
        }
        String icon = section.getString("icon", "NAME_TAG").trim().toUpperCase(Locale.ROOT);
        if (!MATERIAL_NAMES.contains(icon)) {
            throw new IllegalArgumentException("icon must be a valid item material: " + icon);
        }
        if (Bukkit.getServer() != null) {
            Material material = Material.matchMaterial(icon);
            if (material == null || !material.isItem()) {
                throw new IllegalArgumentException("icon must be a valid item material: " + icon);
            }
        }
        AnimationMode animationMode = null;
        int animationPeriodTicks = 0;
        if (type == StyleType.ANIMATED) {
            try {
                animationMode = AnimationMode.valueOf(
                        section.getString("animation-mode", "FLOW").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unknown animation-mode", exception);
            }
            animationPeriodTicks = section.getInt("animation-period-ticks", 60);
        } else if (section.contains("animation-mode")
                || section.contains("animation-period-ticks")) {
            throw new IllegalArgumentException(
                    "animation-mode and animation-period-ticks require type ANIMATED");
        }
        return new NameStyle(
                key,
                section.getString("display-name", key),
                type,
                colors,
                decorations,
                icon,
                parseStylePermissions(section),
                animationMode,
                animationPeriodTicks
        );
    }

    private static List<String> parseStylePermissions(ConfigurationSection section) {
        List<String> permissions = new ArrayList<>();
        appendStylePermissions(permissions, section.get("permission"), "permission");
        appendStylePermissions(permissions, section.get("permissions"), "permissions");
        return permissions;
    }

    private static void appendStylePermissions(List<String> target, Object configured, String key) {
        if (configured == null) {
            return;
        }
        if (configured instanceof String permission) {
            target.add(permission);
            return;
        }
        if (configured instanceof List<?> permissions) {
            for (Object permission : permissions) {
                if (!(permission instanceof String value)) {
                    throw new IllegalArgumentException(key + " must contain only permission strings");
                }
                target.add(value);
            }
            return;
        }
        throw new IllegalArgumentException(key + " must be a permission string or list");
    }

    private static List<PermissionGroup> loadGroups(
            FileConfiguration config,
            Set<String> configuredStyleKeys
    ) {
        List<PermissionGroup> groups = new ArrayList<>();
        ConfigurationSection root = config.getConfigurationSection("groups");
        if (root == null) {
            return groups;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null) {
                continue;
            }
            if (section.contains("max-owned-styles")) {
                throw new IllegalArgumentException("Invalid group '" + name
                        + "': max-owned-styles is no longer supported; use unlocked-styles");
            }
            if (section.contains("allowed-types")) {
                throw new IllegalArgumentException("Invalid group '" + name
                        + "': allowed-types is no longer supported; use unlocked-styles");
            }
            Set<String> unlockedStyles = parseUnlockedStyles(name, section, configuredStyleKeys);
            String defaultStyle = parseDefaultStyle(name, section, configuredStyleKeys, unlockedStyles);
            groups.add(new PermissionGroup(
                    name,
                    section.getString("permission", ""),
                    section.getInt("priority", 0),
                    unlockedStyles,
                    defaultStyle
            ));
        }
        return groups;
    }

    private static Set<String> parseUnlockedStyles(
            String groupName,
            ConfigurationSection section,
            Set<String> configuredStyleKeys
    ) {
        Object configured = section.get("unlocked-styles");
        if (configured == null) {
            return Set.of();
        }
        if (!(configured instanceof List<?> entries)) {
            throw new IllegalArgumentException("Invalid group '" + groupName
                    + "': unlocked-styles must be a list");
        }

        Set<String> unlockedStyles = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof String raw)) {
                throw new IllegalArgumentException("Invalid group '" + groupName
                        + "': unlocked-styles must contain only style keys");
            }
            String styleKey = raw.trim().toLowerCase(Locale.ROOT);
            if (!configuredStyleKeys.contains(styleKey)) {
                throw new IllegalArgumentException("Invalid group '" + groupName
                        + "': unknown style key in unlocked-styles: " + raw);
            }
            unlockedStyles.add(styleKey);
        }
        return Set.copyOf(unlockedStyles);
    }

    private static String parseDefaultStyle(
            String groupName,
            ConfigurationSection section,
            Set<String> configuredStyleKeys,
            Set<String> unlockedStyles
    ) {
        Object configured = section.get("default-style");
        if (configured == null) {
            return "";
        }
        if (!(configured instanceof String raw)) {
            throw new IllegalArgumentException("Invalid group '" + groupName
                    + "': default-style must be one scalar style key");
        }
        String defaultStyle = raw.trim().toLowerCase(Locale.ROOT);
        if (defaultStyle.isEmpty()) {
            return "";
        }
        if (!configuredStyleKeys.contains(defaultStyle)) {
            throw new IllegalArgumentException("Invalid group '" + groupName
                    + "': unknown style key in default-style: " + defaultStyle);
        }
        if (!unlockedStyles.contains(defaultStyle)) {
            throw new IllegalArgumentException("Invalid group '" + groupName
                    + "': default-style must also be listed in unlocked-styles: " + defaultStyle);
        }
        return defaultStyle;
    }

    public PluginSettings settings() {
        return settings;
    }

    public StorageSettings storage() {
        return storage;
    }

    public Map<String, NameStyle> styles() {
        return styles;
    }

    public List<PermissionGroup> groups() {
        return groups;
    }

    public PermissionGroup groupFor(CommandSender sender) {
        // The empty-permission group is a fallback, not a competing permission group.
        // Keep it out of the priority scan so a malformed/high fallback priority cannot
        // shadow a group the sender actually owns.
        return groups.stream()
                .filter(group -> !group.permission().isBlank()
                        && sender.hasPermission(group.permission()))
                .findFirst()
                .orElseGet(() -> groups.stream()
                        .filter(group -> group.permission().isBlank())
                        .findFirst()
                        .orElseThrow());
    }
}
