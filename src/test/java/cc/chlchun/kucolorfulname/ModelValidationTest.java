package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.AnimationMode;
import cc.chlchun.kucolorfulname.api.NameStyle;
import cc.chlchun.kucolorfulname.api.PlayerNameSnapshot;
import cc.chlchun.kucolorfulname.api.StyleType;
import cc.chlchun.kucolorfulname.api.StyleUnlockSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelValidationTest {
    @Test
    void validatesStyleShapeAndNormalizesIdentifiers() {
        NameStyle style = new NameStyle(
                " Ruby ",
                " Ruby ",
                StyleType.SOLID,
                List.of(0xFF0000),
                Set.of(),
                "red_dye",
                " KuColorfulName.Style.Ruby "
        );

        assertEquals("ruby", style.key());
        assertEquals("Ruby", style.displayName());
        assertEquals("RED_DYE", style.icon());
        assertEquals("kucolorfulname.style.ruby", style.permission());
        assertEquals(List.of("kucolorfulname.style.ruby"), style.permissions());
        assertTrue(style.hasPermissionUnlock());
        NameStyle alternatives = new NameStyle(
                "dual", "Dual", StyleType.SOLID, List.of(0), Set.of(), "NAME_TAG",
                List.of(" Example.Color.Primary ", "example.color.secondary", "EXAMPLE.COLOR.PRIMARY")
        );
        assertEquals(List.of("example.color.primary", "example.color.secondary"),
                alternatives.permissions());
        assertEquals("example.color.primary", alternatives.permission());
        NameStyle unrestricted = new NameStyle(
                "free", "Free", StyleType.SOLID, List.of(0), Set.of(), "NAME_TAG"
        );
        assertEquals("", unrestricted.permission());
        assertTrue(unrestricted.permissions().isEmpty());
        assertTrue(!unrestricted.hasPermissionUnlock());
        assertThrows(IllegalArgumentException.class, () -> new NameStyle(
                "solid", "Solid", StyleType.SOLID, List.of(0, 1), Set.of(), "NAME_TAG"));
        assertThrows(IllegalArgumentException.class, () -> new NameStyle(
                "bad", "Bad", StyleType.SOLID, List.of(0x1000000), Set.of(), "NAME_TAG"));
        assertThrows(IllegalArgumentException.class, () -> new NameStyle(
                "bad-permission", "Bad Permission", StyleType.SOLID, List.of(0), Set.of(),
                "NAME_TAG", "not a permission"));
        assertThrows(IllegalArgumentException.class, () -> new NameStyle(
                "bad-permissions", "Bad Permissions", StyleType.SOLID, List.of(0), Set.of(),
                "NAME_TAG", List.of("valid.permission", "not a permission")));
        NameStyle animated = new NameStyle(
                "wave", "Wave", StyleType.ANIMATED, List.of(0, 1), Set.of(), "ECHO_SHARD",
                List.of(), AnimationMode.FLOW, 40);
        assertEquals(AnimationMode.FLOW, animated.animationMode());
        assertEquals(40, animated.animationPeriodTicks());
        assertThrows(NullPointerException.class, () -> new NameStyle(
                "bad-animation", "Bad", StyleType.ANIMATED, List.of(0, 1), Set.of(),
                "NAME_TAG", List.of(), null, 40));
        assertThrows(IllegalArgumentException.class, () -> new NameStyle(
                "bad-period", "Bad", StyleType.ANIMATED, List.of(0, 1), Set.of(),
                "NAME_TAG", List.of(), AnimationMode.FLOW, 1));
    }

    @Test
    void validatesGrantLifetimeAndKeepsGrantIndependentActiveSelection() {
        UUID player = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        NameGrant grant = new NameGrant(
                UUID.randomUUID(), player, " Ruby ", now, now.plus(Duration.ofDays(7)), null, " test "
        );
        PlayerNameSnapshot snapshot = new PlayerNameSnapshot(
                player, List.of(grant), Optional.of("missing")
        );

        assertEquals("ruby", grant.styleKey());
        assertEquals("test", grant.source());
        assertEquals("missing", snapshot.activeStyleKey().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new NameGrant(
                UUID.randomUUID(), player, "ruby", now, now, null, "test"));
        assertThrows(NullPointerException.class, () -> new NameGrant(
                UUID.randomUUID(), player, "ruby", now, null, null, "test"));
    }

    @Test
    void resolvesIndependentUnlockSources() {
        assertEquals(StyleUnlockSource.PERMISSION,
                StyleUnlockSource.resolve(true, false).orElseThrow());
        assertEquals(StyleUnlockSource.GRANT,
                StyleUnlockSource.resolve(false, true).orElseThrow());
        assertEquals(StyleUnlockSource.PERMISSION_AND_GRANT,
                StyleUnlockSource.resolve(true, true).orElseThrow());
        assertTrue(StyleUnlockSource.resolve(false, false).isEmpty());
    }
}
