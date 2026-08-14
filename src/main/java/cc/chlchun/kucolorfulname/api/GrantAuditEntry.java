package cc.chlchun.kucolorfulname.api;

import java.time.Instant;
import java.util.UUID;

public record GrantAuditEntry(
        UUID id,
        UUID playerId,
        String styleKey,
        AuditAction action,
        Instant occurredAt,
        UUID actorId,
        String source,
        Instant previousExpiresAt,
        Instant newExpiresAt
) { }
