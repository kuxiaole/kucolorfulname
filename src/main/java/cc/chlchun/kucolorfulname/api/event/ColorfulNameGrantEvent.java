package cc.chlchun.kucolorfulname.api.event;

import cc.chlchun.kucolorfulname.api.NameGrant;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class ColorfulNameGrantEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final NameGrant grant;

    public ColorfulNameGrantEvent(boolean async, NameGrant grant) {
        super(async);
        this.grant = grant;
    }

    public NameGrant grant() { return grant; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
