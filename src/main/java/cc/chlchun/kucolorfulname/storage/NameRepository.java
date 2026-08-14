package cc.chlchun.kucolorfulname.storage;

import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.GrantAuditEntry;
import cc.chlchun.kucolorfulname.api.PlayerNameSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface NameRepository extends AutoCloseable {
    CompletionStage<List<NameGrant>> grants(UUID playerId);

    CompletionStage<PlayerNameSnapshot> snapshot(UUID playerId);

    CompletionStage<NameGrant> saveGrant(NameGrant grant);

    CompletionStage<Optional<NameGrant>> extendGrant(
            UUID playerId,
            String styleKey,
            Duration duration,
            UUID actorId,
            String source
    );

    default CompletionStage<Boolean> deleteGrant(UUID playerId, String styleKey) {
        return deleteGrant(playerId, styleKey, null, "api");
    }

    CompletionStage<Boolean> deleteGrant(UUID playerId, String styleKey, UUID actorId, String source);

    CompletionStage<Optional<String>> activeStyle(UUID playerId);

    default CompletionStage<Void> setActiveStyle(UUID playerId, String styleKey) {
        return setActiveStyle(playerId, styleKey, playerId, "api");
    }

    CompletionStage<Void> setActiveStyle(UUID playerId, String styleKey, UUID actorId, String source);

    default CompletionStage<Void> clearActiveStyle(UUID playerId) {
        return clearActiveStyle(playerId, playerId, "api");
    }

    CompletionStage<Void> clearActiveStyle(UUID playerId, UUID actorId, String source);

    CompletionStage<Integer> removeExpired(Instant now);

    CompletionStage<List<GrantAuditEntry>> audit(UUID playerId, int limit);

    @Override
    void close();
}
