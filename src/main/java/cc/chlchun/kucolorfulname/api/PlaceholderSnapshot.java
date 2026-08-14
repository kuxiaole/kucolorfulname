package cc.chlchun.kucolorfulname.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * A fully computed, thread-safe view used by PlaceholderAPI and other read-only integrations.
 * It is produced on the player's scheduler and can be read from any thread afterwards.
 */
public record PlaceholderSnapshot(
        UUID playerId,
        String playerName,
        Component renderedName,
        Optional<String> selectedStyleKey,
        Optional<String> activeStyleKey,
        Optional<NameGrant> activeGrant,
        Optional<StyleUnlockSource> unlockSource,
        PlaceholderStatus status,
        String group,
        int ownedGrantCount,
        Map<String, PlaceholderStyleState> styles,
        Instant updatedAt
) {
    public PlaceholderSnapshot {
        playerId = Objects.requireNonNull(playerId, "playerId");
        playerName = Objects.requireNonNull(playerName, "playerName");
        renderedName = Objects.requireNonNull(renderedName, "renderedName");
        selectedStyleKey = normalizeKey(selectedStyleKey, "selectedStyleKey");
        activeStyleKey = normalizeKey(activeStyleKey, "activeStyleKey");
        activeGrant = Objects.requireNonNull(activeGrant, "activeGrant");
        unlockSource = Objects.requireNonNull(unlockSource, "unlockSource");
        status = Objects.requireNonNull(status, "status");
        group = Objects.requireNonNull(group, "group");
        if (ownedGrantCount < 0) {
            throw new IllegalArgumentException("ownedGrantCount cannot be negative");
        }
        Objects.requireNonNull(styles, "styles");
        Map<String, PlaceholderStyleState> normalizedStyles = new LinkedHashMap<>();
        styles.forEach((key, value) -> {
            String normalized = Objects.requireNonNull(key, "style key").trim()
                    .toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("style key cannot be blank");
            }
            normalizedStyles.put(normalized, Objects.requireNonNull(value, "style state"));
        });
        styles = Map.copyOf(normalizedStyles);
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public Optional<PlaceholderStyleState> style(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(styles.get(key.trim().toLowerCase(Locale.ROOT)));
    }

    public Optional<String> activeStyleAt(Instant instant) {
        return selectedStyleKey.flatMap(this::style)
                .filter(state -> state.availableAt(instant))
                .map(PlaceholderStyleState::styleKey);
    }

    public Optional<StyleUnlockSource> activeUnlockSourceAt(Instant instant) {
        return selectedStyleKey.flatMap(this::style)
                .flatMap(state -> state.unlockSourceAt(instant));
    }

    public PlaceholderStatus statusAt(Instant instant) {
        if (activeStyleAt(instant).isPresent()) {
            return PlaceholderStatus.ACTIVE;
        }
        if (selectedStyleKey.isPresent()) {
            return PlaceholderStatus.LOCKED;
        }
        return styles.values().stream().anyMatch(state -> state.availableAt(instant))
                ? PlaceholderStatus.AVAILABLE : PlaceholderStatus.NO_ACTIVE_STYLE;
    }

    public Component renderedNameAt(Instant instant) {
        return activeStyleAt(instant).isPresent() ? renderedName : Component.text(playerName);
    }

    public Optional<NameGrant> activeGrantAt(Instant instant) {
        return activeUnlockSourceAt(instant)
                .filter(StyleUnlockSource::includesGrant)
                .flatMap(ignored -> activeGrant)
                .filter(grant -> grant.expiresAt().isAfter(instant));
    }

    public int ownedGrantCountAt(Instant instant) {
        return (int) styles.values().stream().filter(state -> state.hasGrantAt(instant)).count();
    }

    @Override
    public Component renderedName() {
        return renderedNameAt(Instant.now());
    }

    @Override
    public Optional<String> activeStyleKey() {
        return activeStyleAt(Instant.now());
    }

    @Override
    public Optional<NameGrant> activeGrant() {
        return activeGrantAt(Instant.now());
    }

    @Override
    public Optional<StyleUnlockSource> unlockSource() {
        return activeUnlockSourceAt(Instant.now());
    }

    @Override
    public PlaceholderStatus status() {
        return statusAt(Instant.now());
    }

    @Override
    public int ownedGrantCount() {
        return ownedGrantCountAt(Instant.now());
    }

    private static Optional<String> normalizeKey(Optional<String> value, String label) {
        Objects.requireNonNull(value, label);
        return value.map(key -> {
            String normalized = Objects.requireNonNull(key, label).trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(label + " cannot contain a blank key");
            }
            return normalized;
        });
    }
}
