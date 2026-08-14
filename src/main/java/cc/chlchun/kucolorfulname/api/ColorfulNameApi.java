package cc.chlchun.kucolorfulname.api;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public interface ColorfulNameApi {

    Collection<NameStyle> styles();

    Optional<NameStyle> style(String key);

    CompletionStage<List<NameGrant>> grants(UUID playerId);

    /** Reads a consistent storage snapshot without populating the runtime cache. */
    CompletionStage<PlayerNameSnapshot> snapshot(UUID playerId);

    /** Reloads grants and the active style into the non-blocking runtime cache. */
    CompletionStage<Void> refresh(UUID playerId);

    /** Returns the last loaded grants without doing I/O. */
    List<NameGrant> cachedGrants(UUID playerId);

    /** Returns the last loaded active grant without doing I/O. */
    Optional<NameGrant> cachedActiveGrant(UUID playerId);

    /** Returns the selected active style key even when it is unlocked only by permission. */
    Optional<String> cachedActiveStyleKey(UUID playerId);

    /** Returns whether a runtime cache entry exists for the player. */
    boolean isCached(UUID playerId);

    /**
     * Returns the last fully computed placeholder snapshot without doing I/O or touching a
     * Bukkit player object. The snapshot is safe to read from asynchronous integrations.
     */
    default Optional<PlaceholderSnapshot> cachedPlaceholderSnapshot(UUID playerId) {
        return Optional.empty();
    }

    /** Checks the group and whether permission or a valid grant unlocks the style without doing I/O. */
    boolean canUseCached(Player player, String styleKey);

    /** Checks whether any configured per-style permission or administrator bypass unlocks the style. */
    boolean hasStylePermission(Player player, String styleKey);

    /** Returns the effective group/style-permission/grant unlock route. */
    Optional<StyleUnlockSource> unlockSourceCached(Player player, String styleKey);

    CompletionStage<Optional<NameGrant>> activeGrant(UUID playerId);

    /**
     * Returns the active database grant after refreshing storage.
     * The result is empty when the selected style is unlocked only by permission.
     */
    default CompletionStage<Optional<NameGrant>> activeDatabaseGrant(UUID playerId) {
        return activeGrant(playerId);
    }

    /**
     * Creates or replaces a time-limited grant.
     *
     * @param duration non-null, positive lifetime; permanent access must be provided by the
     *                 configured per-style permission
     */
    CompletionStage<NameGrant> grant(
            UUID playerId,
            String styleKey,
            Duration duration,
            UUID grantedBy,
            String source
    );

    CompletionStage<Boolean> revoke(UUID playerId, String styleKey);

    CompletionStage<Boolean> revoke(
            UUID playerId,
            String styleKey,
            UUID actorId,
            String source
    );

    /** Extends an existing time-limited grant by a non-null, positive duration. */
    CompletionStage<Optional<NameGrant>> extend(
            UUID playerId,
            String styleKey,
            Duration duration,
            UUID actorId,
            String source
    );

    CompletionStage<List<GrantAuditEntry>> audit(UUID playerId, int limit);

    CompletionStage<ActivationResult> activate(Player player, String styleKey);

    CompletionStage<Void> deactivate(UUID playerId);

    /**
     * Renders from the latest cached availability snapshot without touching a Bukkit player.
     * Before the first snapshot exists, a valid cached grant remains the fallback unlock route.
     */
    Component renderActive(UUID playerId, String fallbackName);

    /**
     * Legacy alias for {@link #renderActive(UUID, String)}.
     */
    default Component renderActiveFromGrantCache(UUID playerId, String fallbackName) {
        return renderActive(playerId, fallbackName);
    }

    /**
     * Renders the active name while enforcing the group and permission-or-grant unlock rules.
     * Call this on the player's scheduler; integrations that may run asynchronously should use
     * {@link #cachedPlaceholderSnapshot(UUID)} instead.
     */
    Component renderActive(Player player);
}
