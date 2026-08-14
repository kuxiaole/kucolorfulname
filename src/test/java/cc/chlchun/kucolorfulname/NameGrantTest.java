package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.api.NameGrant;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameGrantTest {
    @Test
    void expiringGrantIsValidOnlyBeforeItsExpiry() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID player = UUID.randomUUID();
        NameGrant expiring = new NameGrant(UUID.randomUUID(), player, "ruby", now,
                now.plus(Duration.ofHours(1)), null, "test");

        assertTrue(expiring.validAt(now));
        assertTrue(expiring.validAt(now.plus(Duration.ofMinutes(59))));
        assertFalse(expiring.validAt(now.plus(Duration.ofHours(1))));
    }

    @Test
    void rejectsMissingOrNonFutureExpiry() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        UUID player = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> new NameGrant(
                UUID.randomUUID(), player, "ruby", now, null, null, "test"));
        assertThrows(IllegalArgumentException.class, () -> new NameGrant(
                UUID.randomUUID(), player, "ruby", now, now, null, "test"));
        assertThrows(IllegalArgumentException.class, () -> new NameGrant(
                UUID.randomUUID(), player, "ruby", now, now.minusSeconds(1), null, "test"));
    }
}
