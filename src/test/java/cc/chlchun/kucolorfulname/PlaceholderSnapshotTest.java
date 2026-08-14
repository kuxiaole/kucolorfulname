package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.PlaceholderSnapshot;
import cc.chlchun.kucolorfulname.api.PlaceholderStatus;
import cc.chlchun.kucolorfulname.api.PlaceholderStyleState;
import cc.chlchun.kucolorfulname.api.StyleUnlockSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaceholderSnapshotTest {
    @Test
    void normalizesKeysAndExposesCaseInsensitiveStyleLookup() {
        Instant expiry = Instant.now().plusSeconds(3600);
        PlaceholderStyleState state = new PlaceholderStyleState(
                "Aurora", true, true, true, Optional.of(expiry),
                Optional.of(StyleUnlockSource.PERMISSION_AND_GRANT));
        PlaceholderSnapshot snapshot = new PlaceholderSnapshot(
                UUID.randomUUID(), "Player", Component.text("Player"),
                Optional.of("Aurora"), Optional.of("Aurora"), Optional.empty(),
                Optional.of(StyleUnlockSource.PERMISSION_AND_GRANT), PlaceholderStatus.ACTIVE,
                "vip", 1, Map.of("Aurora", state), Instant.now());

        assertEquals("aurora", snapshot.activeStyleKey().orElseThrow());
        assertEquals(true, snapshot.style("AURORA").orElseThrow().available());
    }

    @Test
    void rejectsInconsistentUnlockFlags() {
        assertThrows(IllegalArgumentException.class, () -> new PlaceholderStyleState(
                "ruby", true, true, false, Optional.empty(),
                Optional.of(StyleUnlockSource.GRANT)));
    }

    @Test
    void recordsGrantCountAndActiveGrantWithoutMutableCollections() {
        NameGrant grant = new NameGrant(
                UUID.randomUUID(), UUID.randomUUID(), "ruby", Instant.now(),
                Instant.now().plusSeconds(60), null, "test");
        PlaceholderSnapshot snapshot = new PlaceholderSnapshot(
                grant.playerId(), "Player", Component.text("Player"),
                Optional.of("ruby"), Optional.of("ruby"), Optional.of(grant),
                Optional.of(StyleUnlockSource.GRANT), PlaceholderStatus.ACTIVE,
                "default", 1, Map.of("ruby", new PlaceholderStyleState(
                        "ruby", true, false, true, Optional.of(grant.expiresAt()),
                        Optional.of(StyleUnlockSource.GRANT))), Instant.now());

        assertEquals(1, snapshot.ownedGrantCount());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.styles().put("ruby", null));
    }

    @Test
    void expiresGrantOnlyStateAtReadTime() {
        Instant expiry = Instant.parse("2026-08-11T12:00:00Z");
        PlaceholderStyleState state = new PlaceholderStyleState(
                "ruby", true, false, true, Optional.of(expiry),
                Optional.of(StyleUnlockSource.GRANT));
        PlaceholderSnapshot snapshot = new PlaceholderSnapshot(
                UUID.randomUUID(), "Player", Component.text("Colored"),
                Optional.of("ruby"), Optional.of("ruby"), Optional.empty(),
                Optional.of(StyleUnlockSource.GRANT), PlaceholderStatus.ACTIVE,
                "default", 1, Map.of("ruby", state), expiry.minusSeconds(60));

        assertEquals(PlaceholderStatus.ACTIVE, snapshot.statusAt(expiry.minusSeconds(1)));
        assertEquals(PlaceholderStatus.LOCKED, snapshot.statusAt(expiry));
        assertEquals(Optional.empty(), snapshot.activeStyleAt(expiry));
        assertEquals(0, snapshot.ownedGrantCountAt(expiry));
    }
}
