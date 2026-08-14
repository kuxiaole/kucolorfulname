package cc.chlchun.kucolorfulname.gui;

import cc.chlchun.kucolorfulname.api.NameStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Keeps player and administrator repository visibility rules separate and testable. */
final class RepositoryStyleCatalog {
    private RepositoryStyleCatalog() { }

    static List<NameStyle> playerStyles(
            Collection<NameStyle> configuredStyles,
            Predicate<String> canUse,
            Optional<String> activeStyleKey
    ) {
        Objects.requireNonNull(configuredStyles, "configuredStyles");
        Objects.requireNonNull(canUse, "canUse");
        Objects.requireNonNull(activeStyleKey, "activeStyleKey");

        List<NameStyle> available = configuredStyles.stream()
                .filter(style -> canUse.test(style.key()))
                .toList();
        if (activeStyleKey.isEmpty()) {
            return available;
        }

        String active = activeStyleKey.get();
        List<NameStyle> ordered = new ArrayList<>(available.size());
        available.stream().filter(style -> style.key().equals(active)).findFirst()
                .ifPresent(ordered::add);
        available.stream().filter(style -> !style.key().equals(active)).forEach(ordered::add);
        return List.copyOf(ordered);
    }

    static List<NameStyle> playerLockedStyles(
            Collection<NameStyle> configuredStyles,
            Predicate<String> canUse
    ) {
        Objects.requireNonNull(configuredStyles, "configuredStyles");
        Objects.requireNonNull(canUse, "canUse");

        return configuredStyles.stream()
                .filter(style -> !canUse.test(style.key()))
                .toList();
    }

    static List<NameStyle> adminStyles(Collection<NameStyle> configuredStyles) {
        return List.copyOf(Objects.requireNonNull(configuredStyles, "configuredStyles"));
    }
}
