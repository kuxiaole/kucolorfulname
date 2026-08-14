package cc.chlchun.kucolorfulname.api.event;

import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.NameStyle;
import cc.chlchun.kucolorfulname.api.StyleUnlockSource;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class ColorfulNameChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Optional<NameGrant> grant;
    private final NameStyle style;
    private final StyleUnlockSource unlockSource;

    public ColorfulNameChangedEvent(
            Player player,
            Optional<NameGrant> grant,
            NameStyle style,
            StyleUnlockSource unlockSource
    ) {
        this.player = player;
        this.grant = grant;
        this.style = style;
        this.unlockSource = unlockSource;
    }

    public Player player() { return player; }
    public Optional<NameGrant> grant() { return grant; }
    public NameStyle style() { return style; }
    public StyleUnlockSource unlockSource() { return unlockSource; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
