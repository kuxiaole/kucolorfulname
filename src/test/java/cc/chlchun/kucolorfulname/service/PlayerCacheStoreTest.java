package cc.chlchun.kucolorfulname.service;

import cc.chlchun.kucolorfulname.api.NameGrant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerCacheStoreTest {
    @Test
    void retiredSessionCannotPublishIntoNewSession() {
        PlayerCacheStore store = new PlayerCacheStore();
        UUID playerId = UUID.randomUUID();
        PlayerCacheStore.Session retired = store.open(playerId);
        store.unload(playerId);
        PlayerCacheStore.Session current = store.open(playerId);

        assertNotSame(retired, current);
        assertFalse(store.update(retired, state -> state.withData(List.of(grant(playerId)), "rainbow")));
        assertTrue(store.update(current, state -> state.withData(List.of(), null)));
        assertTrue(store.state(playerId).grants().isEmpty());
    }

    @Test
    void unloadReclaimsSessionMetadata() {
        PlayerCacheStore store = new PlayerCacheStore();
        for (int index = 0; index < 500; index++) {
            UUID playerId = UUID.randomUUID();
            store.open(playerId);
            store.unload(playerId);
        }

        assertEquals(0, store.sessionCount());
    }

    @Test
    void replaceDataReportsOnlyPublishedChanges() {
        PlayerCacheStore store = new PlayerCacheStore();
        UUID playerId = UUID.randomUUID();
        PlayerCacheStore.Session session = store.open(playerId);
        NameGrant grant = grant(playerId);

        assertTrue(store.replaceDataIfChanged(session, List.of(grant), "rainbow"));
        assertFalse(store.replaceDataIfChanged(session, List.of(grant), "rainbow"));
        assertTrue(store.replaceDataIfChanged(session, List.of(), null));
    }

    private NameGrant grant(UUID playerId) {
        Instant now = Instant.now();
        return new NameGrant(
                UUID.randomUUID(), playerId, "rainbow", now, now.plusSeconds(60), null, "test");
    }
}
