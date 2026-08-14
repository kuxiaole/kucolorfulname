package cc.chlchun.kucolorfulname.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.kyori.adventure.text.format.TextDecoration;

public record NameStyle(
        String key,
        String displayName,
        StyleType type,
        List<Integer> colors,
        Set<TextDecoration> decorations,
        String icon,
        List<String> permissions,
        AnimationMode animationMode,
        int animationPeriodTicks
) {
    public NameStyle(
            String key,
            String displayName,
            StyleType type,
            List<Integer> colors,
            Set<TextDecoration> decorations,
            String icon
    ) {
        this(key, displayName, type, colors, decorations, icon, List.of(), null, 0);
    }

    /** Convenience constructor for integrations that configure one permission. */
    public NameStyle(
            String key,
            String displayName,
            StyleType type,
            List<Integer> colors,
            Set<TextDecoration> decorations,
            String icon,
            String permission
    ) {
        this(key, displayName, type, colors, decorations, icon,
                List.of(Objects.requireNonNull(permission, "permission")), null, 0);
    }

    /** Convenience constructor for static styles with multiple alternative permissions. */
    public NameStyle(
            String key,
            String displayName,
            StyleType type,
            List<Integer> colors,
            Set<TextDecoration> decorations,
            String icon,
            List<String> permissions
    ) {
        this(key, displayName, type, colors, decorations, icon, permissions, null, 0);
    }

    public NameStyle {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(colors, "colors");
        Objects.requireNonNull(decorations, "decorations");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(permissions, "permissions");
        key = key.trim().toLowerCase(Locale.ROOT);
        displayName = displayName.trim();
        icon = icon.trim().toUpperCase(Locale.ROOT);
        colors = List.copyOf(colors);
        decorations = Set.copyOf(decorations);
        LinkedHashSet<String> normalizedPermissions = new LinkedHashSet<>();
        for (String permission : permissions) {
            String normalized = Objects.requireNonNull(permission, "permission")
                    .trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            if (!normalized.matches("[a-z0-9_.-]+")) {
                throw new IllegalArgumentException("Style has an invalid permission: " + key);
            }
            normalizedPermissions.add(normalized);
        }
        permissions = List.copyOf(new ArrayList<>(normalizedPermissions));
        if (key.isEmpty() || displayName.isEmpty() || icon.isEmpty()) {
            throw new IllegalArgumentException("Style key, display name and icon cannot be blank");
        }
        if ((type == StyleType.SOLID && colors.size() != 1)
                || (type != StyleType.SOLID && colors.size() < 2)) {
            throw new IllegalArgumentException("Style has an invalid color count: " + key);
        }
        if (colors.stream().anyMatch(color -> color == null || color < 0 || color > 0xFFFFFF)) {
            throw new IllegalArgumentException("Style contains an invalid RGB color: " + key);
        }
        if (type == StyleType.ANIMATED) {
            Objects.requireNonNull(animationMode, "Animated style requires animationMode");
            if (animationPeriodTicks < 2 || animationPeriodTicks > 1200) {
                throw new IllegalArgumentException(
                        "Animated style animationPeriodTicks must be between 2 and 1200: " + key);
            }
        } else if (animationMode != null || animationPeriodTicks != 0) {
            throw new IllegalArgumentException(
                    "Only ANIMATED styles can configure animation fields: " + key);
        }
    }

    public boolean hasPermissionUnlock() {
        return !permissions.isEmpty();
    }

    /**
     * Returns the first configured permission for binary compatibility with the original API.
     * New integrations should use {@link #permissions()}.
     */
    public String permission() {
        return permissions.isEmpty() ? "" : permissions.getFirst();
    }
}
