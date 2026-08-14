package cc.chlchun.kucolorfulname.config;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record PermissionGroup(
        String name,
        String permission,
        int priority,
        Set<String> unlockedStyles,
        String defaultStyle
) {
    /** Convenience constructor for groups without an automatic style. */
    public PermissionGroup(String name, String permission, int priority, Set<String> unlockedStyles) {
        this(name, permission, priority, unlockedStyles, "");
    }

    public PermissionGroup {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(unlockedStyles, "unlockedStyles");
        Objects.requireNonNull(defaultStyle, "defaultStyle");
        name = name.trim();
        permission = permission.trim();
        defaultStyle = defaultStyle.trim().toLowerCase(Locale.ROOT);
        Set<String> normalizedStyles = new LinkedHashSet<>();
        for (String style : unlockedStyles) {
            Objects.requireNonNull(style, "unlockedStyles cannot contain null");
            String normalized = style.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("unlockedStyles cannot contain blank style keys");
            }
            normalizedStyles.add(normalized);
        }
        unlockedStyles = Set.copyOf(normalizedStyles);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Group name cannot be blank");
        }
    }
}
