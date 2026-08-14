package cc.chlchun.kucolorfulname.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record NameGrant(
        UUID id,
        UUID playerId,
        String styleKey,
        Instant grantedAt,
        Instant expiresAt,
        UUID grantedBy,
        String source
) {
    public NameGrant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(styleKey, "styleKey");
        Objects.requireNonNull(grantedAt, "grantedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(source, "source");
        styleKey = styleKey.trim().toLowerCase(Locale.ROOT);
        source = source.trim();
        if (styleKey.isEmpty()) {
            throw new IllegalArgumentException("styleKey cannot be blank");
        }
        if (source.isEmpty()) {
            throw new IllegalArgumentException("source cannot be blank");
        }
        if (!expiresAt.isAfter(grantedAt)) {
            throw new IllegalArgumentException("expiresAt must be after grantedAt");
        }
    }

    public boolean validAt(Instant instant) {
        return expiresAt.isAfter(instant);
    }
}
