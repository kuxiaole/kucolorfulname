package cc.chlchun.kucolorfulname.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after the selected style is cleared without revoking ownership. */
public final class ColorfulNameDeactivateEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final String previousStyleKey;

    public ColorfulNameDeactivateEvent(UUID playerId, String previousStyleKey) {
        this.playerId = playerId;
        this.previousStyleKey = previousStyleKey;
    }

    public UUID playerId() { return playerId; }
    public String previousStyleKey() { return previousStyleKey; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
