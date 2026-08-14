package cc.chlchun.kucolorfulname.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after a grant has been revoked and removed from storage. */
public final class ColorfulNameRevokeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final String styleKey;
    private final UUID actorId;
    private final String source;

    public ColorfulNameRevokeEvent(UUID playerId, String styleKey, UUID actorId, String source) {
        this.playerId = playerId;
        this.styleKey = styleKey;
        this.actorId = actorId;
        this.source = source;
    }

    public UUID playerId() { return playerId; }
    public String styleKey() { return styleKey; }
    public UUID actorId() { return actorId; }
    public String source() { return source; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
