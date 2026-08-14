package cc.chlchun.kucolorfulname.api;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable storage snapshot that does not populate the runtime player cache. */
public record PlayerNameSnapshot(
        UUID playerId,
        List<NameGrant> grants,
        Optional<String> activeStyleKey
) {
    public PlayerNameSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(grants, "grants");
        Objects.requireNonNull(activeStyleKey, "activeStyleKey");
        grants = List.copyOf(grants);
        activeStyleKey = activeStyleKey.map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty());
    }
}
