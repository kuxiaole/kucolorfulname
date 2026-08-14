package cc.chlchun.kucolorfulname.gui;

import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.PlayerNameSnapshot;
import cc.chlchun.kucolorfulname.config.MenuSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

final class RepositoryHolder implements InventoryHolder {
    enum Mode { PLAYER_HOME, PLAYER_UNLOCKED, PLAYER_LOCKED, ADMIN }

    private final Mode mode;
    private final UUID targetId;
    private final String targetName;
    private final int page;
    private final PlayerNameSnapshot snapshot;
    private final MenuSettings.View menu;
    private final Map<Integer, String> stylesBySlot = new HashMap<>();
    private final Map<String, NameGrant> grantsByStyle = new HashMap<>();
    private Inventory inventory;

    RepositoryHolder(
            Mode mode,
            UUID targetId,
            String targetName,
            int page,
            PlayerNameSnapshot snapshot,
            MenuSettings.View menu
    ) {
        this.mode = mode;
        this.targetId = targetId;
        this.targetName = targetName;
        this.page = page;
        this.snapshot = snapshot;
        this.menu = menu;
    }

    Mode mode() { return mode; }
    UUID targetId() { return targetId; }
    String targetName() { return targetName; }
    int page() { return page; }
    PlayerNameSnapshot snapshot() { return snapshot; }
    MenuSettings.View menu() { return menu; }
    Map<Integer, String> stylesBySlot() { return stylesBySlot; }
    Map<String, NameGrant> grantsByStyle() { return grantsByStyle; }
    void inventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) throw new IllegalStateException("Inventory has not been initialized");
        return inventory;
    }
}
