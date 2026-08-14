package cc.chlchun.kucolorfulname.api.event;

import cc.chlchun.kucolorfulname.api.NameGrant;
import java.time.Duration;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after a temporary grant has been extended and persisted. */
public final class ColorfulNameExtendEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NameGrant grant;
    private final Duration duration;
    private final UUID actorId;
    private final String source;

    public ColorfulNameExtendEvent(NameGrant grant, Duration duration, UUID actorId, String source) {
        this.grant = grant;
        this.duration = duration;
        this.actorId = actorId;
        this.source = source;
    }

    public NameGrant grant() { return grant; }
    public Duration duration() { return duration; }
    public UUID actorId() { return actorId; }
    public String source() { return source; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
