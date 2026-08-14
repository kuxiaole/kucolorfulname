package cc.chlchun.kucolorfulname.api;

/** State exposed by the PlaceholderAPI cache for a player. */
public enum PlaceholderStatus {
    /** The player has no loaded runtime snapshot yet. */
    LOADING,
    /** The selected style is currently active and usable. */
    ACTIVE,
    /** No style is selected, but at least one configured style is usable. */
    AVAILABLE,
    /** A selected style exists but is not currently usable. */
    LOCKED,
    /** The cache is loaded and no style is selected or usable. */
    NO_ACTIVE_STYLE
}
