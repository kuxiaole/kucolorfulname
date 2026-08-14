package cc.chlchun.kucolorfulname.api;

import java.util.Optional;

/** Describes the effective route that currently unlocks a configured name style. */
public enum StyleUnlockSource {
    PERMISSION,
    GRANT,
    PERMISSION_AND_GRANT;

    public boolean includesPermission() {
        return this == PERMISSION || this == PERMISSION_AND_GRANT;
    }

    public boolean includesGrant() {
        return this == GRANT || this == PERMISSION_AND_GRANT;
    }

    public static Optional<StyleUnlockSource> resolve(boolean permissionUnlocked, boolean grantUnlocked) {
        if (permissionUnlocked && grantUnlocked) return Optional.of(PERMISSION_AND_GRANT);
        if (permissionUnlocked) return Optional.of(PERMISSION);
        if (grantUnlocked) return Optional.of(GRANT);
        return Optional.empty();
    }
}
