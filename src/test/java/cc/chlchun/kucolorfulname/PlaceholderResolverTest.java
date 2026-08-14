package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.PlaceholderSnapshot;
import cc.chlchun.kucolorfulname.api.PlaceholderStatus;
import cc.chlchun.kucolorfulname.api.PlaceholderStyleState;
import cc.chlchun.kucolorfulname.api.StyleUnlockSource;
import cc.chlchun.kucolorfulname.hook.PlaceholderResolver;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlaceholderResolverTest {
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

    @Test
    void returnsStableValuesBeforeCacheLoad() {
        assertEquals("Player", resolve(Optional.empty(), "name_raw"));
        assertEquals("loading", resolve(Optional.empty(), "status"));
        assertEquals("false", resolve(Optional.empty(), "loaded"));
        assertEquals("false", resolve(Optional.empty(), "can_use_ruby"));
        assertEquals("", resolve(Optional.empty(), "grant_remaining_ruby"));
        assertNull(resolve(Optional.empty(), "unknown"));
    }

    @Test
    void resolvesPermissionAndDynamicStylePlaceholders() {
        PlaceholderStyleState state = new PlaceholderStyleState(
                "ruby", true, true, false, Optional.empty(),
                Optional.of(StyleUnlockSource.PERMISSION));
        PlaceholderSnapshot snapshot = snapshot(
                state, Optional.empty(), StyleUnlockSource.PERMISSION, NOW.plusSeconds(60));

        assertEquals("active", resolve(snapshot, "status"));
        assertEquals("ruby", resolve(snapshot, "style"));
        assertEquals("permission", resolve(snapshot, "unlock_source"));
        assertEquals("permission", resolve(snapshot, "remaining"));
        assertEquals("true", resolve(snapshot, "can_use_ruby"));
        assertEquals("true", resolve(snapshot, "permission_RUBY"));
        assertEquals("false", resolve(snapshot, "has_grant_ruby"));
        assertEquals("false", resolve(snapshot, "can_use_missing"));
    }

    @Test
    void stopsReportingGrantImmediatelyAtExpiry() {
        Instant expiry = NOW.plusSeconds(30);
        UUID playerId = UUID.randomUUID();
        NameGrant grant = new NameGrant(
                UUID.randomUUID(), playerId, "ruby", NOW.minusSeconds(30),
                expiry, null, "test");
        PlaceholderStyleState state = new PlaceholderStyleState(
                "ruby", true, false, true, Optional.of(expiry),
                Optional.of(StyleUnlockSource.GRANT));
        PlaceholderSnapshot snapshot = new PlaceholderSnapshot(
                playerId, "Player", Component.text("Colored"),
                Optional.of("ruby"), Optional.of("ruby"), Optional.of(grant),
                Optional.of(StyleUnlockSource.GRANT), PlaceholderStatus.ACTIVE,
                "default", 1, Map.of("ruby", state), NOW);

        assertEquals("active", resolveAt(snapshot, "status", expiry.minusSeconds(1)));
        assertEquals("true", resolveAt(snapshot, "can_use_ruby", expiry.minusSeconds(1)));
        assertEquals("1秒", resolveAt(snapshot, "remaining", expiry.minusSeconds(1)));

        assertEquals("locked", resolveAt(snapshot, "status", expiry));
        assertEquals("", resolveAt(snapshot, "style", expiry));
        assertEquals("Player", resolveAt(snapshot, "name_raw", expiry));
        assertEquals("false", resolveAt(snapshot, "can_use_ruby", expiry));
        assertEquals("false", resolveAt(snapshot, "has_grant_ruby", expiry));
        assertEquals("", resolveAt(snapshot, "remaining", expiry));
        assertEquals("0", resolveAt(snapshot, "owned", expiry));
    }

    private PlaceholderSnapshot snapshot(
            PlaceholderStyleState state,
            Optional<NameGrant> grant,
            StyleUnlockSource source,
            Instant updatedAt
    ) {
        return new PlaceholderSnapshot(
                UUID.randomUUID(), "Player", Component.text("Colored"),
                Optional.of("ruby"), Optional.of("ruby"), grant,
                Optional.of(source), PlaceholderStatus.ACTIVE,
                "vip", grant.isPresent() ? 1 : 0, Map.of("ruby", state), updatedAt);
    }

    private String resolve(Optional<PlaceholderSnapshot> snapshot, String parameter) {
        return PlaceholderResolver.resolve(snapshot, "Player", parameter, NOW);
    }

    private String resolve(PlaceholderSnapshot snapshot, String parameter) {
        return resolve(Optional.of(snapshot), parameter);
    }

    private String resolveAt(PlaceholderSnapshot snapshot, String parameter, Instant now) {
        return PlaceholderResolver.resolve(Optional.of(snapshot), "Player", parameter, now);
    }
}
