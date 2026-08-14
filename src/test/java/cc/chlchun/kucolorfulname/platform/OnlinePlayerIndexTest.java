package cc.chlchun.kucolorfulname.platform;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlinePlayerIndexTest {
    @Test
    void resolvesNamesCaseInsensitivelyAndRemovesBothIndexes() {
        OnlinePlayerIndex index = new OnlinePlayerIndex();
        UUID playerId = UUID.randomUUID();

        index.add(playerId, "ColorUser");

        assertEquals(playerId, index.findId("coloruser").orElseThrow());
        assertEquals(List.of("ColorUser"), index.snapshotNames());
        assertTrue(index.remove(playerId));
        assertFalse(index.findId("ColorUser").isPresent());
        assertTrue(index.snapshotIds().isEmpty());
    }

    @Test
    void replacingAPlayersNameRemovesTheOldLookup() {
        OnlinePlayerIndex index = new OnlinePlayerIndex();
        UUID playerId = UUID.randomUUID();

        index.add(playerId, "Before");
        index.add(playerId, "After");

        assertFalse(index.findId("Before").isPresent());
        assertEquals(playerId, index.findId("after").orElseThrow());
        assertEquals(1, index.size());
    }
}
