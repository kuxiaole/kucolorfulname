package cc.chlchun.kucolorfulname.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Immutable cached availability information for one configured style. */
public record PlaceholderStyleState(
        String styleKey,
        boolean available,
        boolean permissionUnlocked,
        boolean grantUnlocked,
        Optional<Instant> grantExpiresAt,
        Optional<StyleUnlockSource> unlockSource
) {
    public PlaceholderStyleState {
        styleKey = Objects.requireNonNull(styleKey, "styleKey").trim().toLowerCase(Locale.ROOT);
        grantExpiresAt = Objects.requireNonNull(grantExpiresAt, "grantExpiresAt");
        unlockSource = Objects.requireNonNull(unlockSource, "unlockSource");
        if (styleKey.isEmpty()) {
            throw new IllegalArgumentException("styleKey cannot be blank");
        }
        if (available != unlockSource.isPresent()) {
            throw new IllegalArgumentException("Available state must match unlock source presence");
        }
        if (unlockSource.isPresent()) {
            StyleUnlockSource source = unlockSource.get();
            if (source.includesPermission() != permissionUnlocked
                    || source.includesGrant() != grantUnlocked) {
                throw new IllegalArgumentException("Unlock source does not match style flags");
            }
        }
    }

    /** Returns whether the cached temporary grant is still valid at the supplied instant. */
    public boolean hasGrantAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return grantExpiresAt.filter(expiry -> expiry.isAfter(instant)).isPresent();
    }

    /** Returns whether this style can currently be used, including grant expiry checks. */
    public boolean availableAt(Instant instant) {
        return permissionUnlocked || (grantUnlocked && hasGrantAt(instant));
    }

    /** Resolves the effective unlock route at the current time without touching Bukkit. */
    public Optional<StyleUnlockSource> unlockSourceAt(Instant instant) {
        return StyleUnlockSource.resolve(permissionUnlocked, grantUnlocked && hasGrantAt(instant));
    }

    /** Time-aware accessor that never reports an expired grant as available. */
    @Override
    public boolean available() {
        return availableAt(Instant.now());
    }

    /** Time-aware accessor that never reports an expired grant as an active unlock route. */
    @Override
    public boolean grantUnlocked() {
        return grantUnlocked && hasGrantAt(Instant.now());
    }

    /** Time-aware accessor that removes an expired grant from the reported unlock route. */
    @Override
    public Optional<StyleUnlockSource> unlockSource() {
        return unlockSourceAt(Instant.now());
    }
}
