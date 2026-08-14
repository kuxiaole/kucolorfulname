package cc.chlchun.kucolorfulname.gui;

import cc.chlchun.kucolorfulname.api.ActivationResult;
import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.NameStyle;
import cc.chlchun.kucolorfulname.api.PlayerNameSnapshot;
import cc.chlchun.kucolorfulname.api.PlaceholderSnapshot;
import cc.chlchun.kucolorfulname.config.ColorfulNameConfig;
import cc.chlchun.kucolorfulname.config.LanguageBundle;
import cc.chlchun.kucolorfulname.config.MenuSettings;
import cc.chlchun.kucolorfulname.platform.DisplayManager;
import cc.chlchun.kucolorfulname.platform.PlayerTasks;
import cc.chlchun.kucolorfulname.render.NameRenderer;
import cc.chlchun.kucolorfulname.service.ColorfulNameService;
import cc.chlchun.kucolorfulname.util.DurationParser;
import cc.chlchun.kucolorfulname.util.PlayerResolver.PlayerTarget;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

public final class RepositoryGui implements Listener {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final DateTimeFormatter AUDIT_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final ColorfulNameService service;
    private final Supplier<ColorfulNameConfig> config;
    private final Supplier<LanguageBundle> language;
    private final Supplier<MenuSettings> menus;
    private final NameRenderer renderer;
    private final DisplayManager display;
    private final PlayerTasks tasks;
    private final ConcurrentMap<UUID, Long> requestTokens = new ConcurrentHashMap<>();
    private final AtomicLong requestSequence = new AtomicLong();
    private final java.util.Set<AdminOperationKey> adminOperations = ConcurrentHashMap.newKeySet();

    public RepositoryGui(
            ColorfulNameService service,
            Supplier<ColorfulNameConfig> config,
            NameRenderer renderer,
            DisplayManager display,
            PlayerTasks tasks,
            Supplier<LanguageBundle> language,
            Supplier<MenuSettings> menus
    ) {
        this.service = service;
        this.config = config;
        this.renderer = renderer;
        this.display = display;
        this.tasks = tasks;
        this.language = language;
        this.menus = menus;
    }

    public void openPlayer(Player player) {
        openPlayer(player, 0);
    }

    private void openPlayer(Player player, int page) {
        UUID playerId = player.getUniqueId();
        long token = nextRequestToken(playerId);
        service.load(playerId).whenComplete((ignored, error) ->
                tasks.scheduler().runForOnlinePlayer(playerId, current -> {
            if (!isCurrentRequest(playerId, token)) {
                return;
            }
            if (error != null) {
                sendError(current, error);
                return;
            }
            current.openInventory(buildPlayerEntry(current, page));
        }));
    }

    public void openAdmin(Player viewer, PlayerTarget target) {
        openAdmin(viewer, target.playerId(), target.label(), 0);
    }

    private void openAdmin(Player viewer, UUID targetId, String targetName, int page) {
        UUID viewerId = viewer.getUniqueId();
        long token = nextRequestToken(viewerId);
        service.snapshot(targetId).whenComplete((snapshot, error) ->
                tasks.scheduler().runForOnlinePlayer(viewerId, current -> {
            if (!isCurrentRequest(viewerId, token)) {
                return;
            }
            if (error != null) {
                sendError(current, error);
                return;
            }
            current.openInventory(buildAdmin(targetId, targetName, page, snapshot));
        }));
    }

    private Inventory buildPlayerEntry(Player player, int requestedPage) {
        MenuSettings settings = menus.get();
        if (settings.categorizedPlayerRepository()) {
            return buildPlayerHome(player, settings);
        }
        return buildPlayerList(
                player, RepositoryHolder.Mode.PLAYER_UNLOCKED, requestedPage, settings);
    }

    private Inventory buildPlayerHome(Player player, MenuSettings settings) {
        MenuSettings.View menu = settings.playerHome();
        PlaceholderSnapshot availability = service.refreshPlaceholderSnapshot(player);
        Instant now = Instant.now();
        Optional<String> active = availability.activeStyleAt(now);
        Predicate<String> canUse = availabilityPredicate(availability, now);
        List<NameStyle> configuredStyles = List.copyOf(service.styles());
        int unlocked = (int) configuredStyles.stream()
                .filter(style -> canUse.test(style.key()))
                .count();
        int locked = configuredStyles.size() - unlocked;
        RepositoryHolder holder = new RepositoryHolder(RepositoryHolder.Mode.PLAYER_HOME,
                player.getUniqueId(), player.getName(), 0, null, menu);
        Inventory inventory = createInventory(holder, menu, Map.of("player", player.getName()));
        placeButton(inventory, menu.button("unlocked"), Map.of(
                "count", unlocked, "unlocked", unlocked, "locked", locked));
        placeButton(inventory, menu.button("locked"), Map.of(
                "count", locked, "unlocked", unlocked, "locked", locked));
        placeButton(inventory, menu.button("disable"), Map.of());
        placePlayerPreview(inventory, menu, player, availability, active, now);
        placeButton(inventory, menu.button("close"), Map.of());
        return inventory;
    }

    private Inventory buildPlayerList(
            Player player,
            RepositoryHolder.Mode mode,
            int requestedPage,
            MenuSettings settings
    ) {
        boolean lockedView = mode == RepositoryHolder.Mode.PLAYER_LOCKED;
        MenuSettings.View menu = lockedView ? settings.playerLocked() : settings.player();
        PlaceholderSnapshot availability = service.refreshPlaceholderSnapshot(player);
        Instant now = Instant.now();
        Optional<String> active = availability.activeStyleAt(now);
        Predicate<String> canUse = availabilityPredicate(availability, now);
        List<NameStyle> configuredStyles = List.copyOf(service.styles());
        List<NameStyle> styles = lockedView
                ? RepositoryStyleCatalog.playerLockedStyles(configuredStyles, canUse)
                : RepositoryStyleCatalog.playerStyles(configuredStyles, canUse, active);
        int page = validPage(requestedPage, styles.size(), menu.styleSlots().size());
        RepositoryHolder holder = new RepositoryHolder(mode,
                player.getUniqueId(), player.getName(), page, null, menu);
        Inventory inventory = createInventory(holder, menu, Map.of("player", player.getName()));
        int start = page * menu.styleSlots().size();
        for (int offset = 0; offset < Math.min(menu.styleSlots().size(), styles.size() - start); offset++) {
            NameStyle style = styles.get(start + offset);
            int slot = menu.styleSlots().get(offset);
            inventory.setItem(slot, lockedView
                    ? lockedStyleItem(style, player.getName(), menu)
                    : playerStyleItem(style, player.getName(), menu,
                            active.filter(style.key()::equals).isPresent()));
            holder.stylesBySlot().put(slot, style.key());
        }
        if (styles.isEmpty() && menu.empty() != null && menu.empty().slot() >= 0) {
            inventory.setItem(menu.empty().slot(), menuItem(menu.empty(), Map.of()));
        }
        placeButton(inventory, menu.button("disable"), Map.of());
        placeButton(inventory, menu.button("home"), Map.of());
        placePlayerPreview(inventory, menu, player, availability, active, now);
        navigation(inventory, menu, page, styles.size(), Map.of("count", styles.size()));
        return inventory;
    }

    private Predicate<String> availabilityPredicate(PlaceholderSnapshot availability, Instant now) {
        return styleKey -> availability.style(styleKey)
                .map(state -> state.availableAt(now))
                .orElse(false);
    }

    private Inventory buildAdmin(
            UUID targetId,
            String targetName,
            int requestedPage,
            PlayerNameSnapshot snapshot
    ) {
        MenuSettings.View menu = menus.get().admin();
        List<NameStyle> styles = RepositoryStyleCatalog.adminStyles(service.styles());
        int page = validPage(requestedPage, styles.size(), menu.styleSlots().size());
        RepositoryHolder holder = new RepositoryHolder(
                RepositoryHolder.Mode.ADMIN, targetId, targetName, page, snapshot, menu);
        Inventory inventory = createInventory(holder, menu, Map.of("player", targetName));
        List<NameGrant> grants = snapshot.grants();
        Optional<String> active = snapshot.activeStyleKey();
        grants.forEach(grant -> holder.grantsByStyle().put(grant.styleKey(), grant));
        int start = page * menu.styleSlots().size();
        for (int offset = 0; offset < Math.min(menu.styleSlots().size(), styles.size() - start); offset++) {
            NameStyle style = styles.get(start + offset);
            NameGrant grant = holder.grantsByStyle().get(style.key());
            int slot = menu.styleSlots().get(offset);
            inventory.setItem(slot, adminStyleItem(style, grant, targetName, menu,
                    active.filter(style.key()::equals).isPresent()));
            holder.stylesBySlot().put(slot, style.key());
        }
        placeButton(inventory, menu.button("audit"), Map.of());
        navigation(inventory, menu, page, styles.size(),
                Map.of("count", styles.size(), "grants", grants.size()));
        return inventory;
    }

    private ItemStack playerStyleItem(
            NameStyle style,
            String playerName,
            MenuSettings.View menu,
            boolean active
    ) {
        Material material = Material.matchMaterial(style.icon());
        ItemStack item = new ItemStack(material == null ? Material.NAME_TAG : material);
        item.editMeta(meta -> {
            meta.displayName(withoutItalics(renderer.render(style.displayName(), style)));
            List<Component> lore = new ArrayList<>();
            lore.add(LanguageBundle.render(menu.text("style-preview", "<gray>预览："))
                    .append(renderer.render(playerName, style)));
            lore.add(LanguageBundle.render(interpolate(menu.text("style-type", "<dark_gray>类型：%type%"),
                    Map.of("type", styleTypeName(style)))));
            lore.add(Component.empty());
            if (active) {
                lore.add(LanguageBundle.render(menu.text("state-active", "<aqua>状态：正在使用")));
            } else {
                lore.add(LanguageBundle.render(menu.text("state-available", "<green>状态：当前可用")));
                lore.add(LanguageBundle.render(menu.text("action-select", "<gray>点击切换使用")));
            }
            meta.lore(lore.stream().map(RepositoryGui::withoutItalics).toList());
            if (active) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    private ItemStack lockedStyleItem(
            NameStyle style,
            String playerName,
            MenuSettings.View menu
    ) {
        Material material = Material.matchMaterial(style.icon());
        ItemStack item = new ItemStack(material == null ? Material.GRAY_DYE : material);
        item.editMeta(meta -> {
            meta.displayName(withoutItalics(renderer.render(style.displayName(), style)));
            List<Component> lore = new ArrayList<>();
            lore.add(LanguageBundle.render(menu.text("style-preview", "<gray>预览："))
                    .append(renderer.render(playerName, style)));
            lore.add(LanguageBundle.render(interpolate(
                    menu.text("style-type", "<dark_gray>类型：<white>%type%"),
                    Map.of("type", styleTypeName(style)))));
            lore.add(Component.empty());
            lore.add(LanguageBundle.render(menu.text("state-locked", "<red>● 未解锁")));
            if (style.hasPermissionUnlock()) {
                lore.add(LanguageBundle.render(interpolate(
                        menu.text("unlock-permissions", "<gray>任一权限：<white>%permissions%"),
                        Map.of(
                                "permission", String.join("、", style.permissions()),
                                "permissions", String.join("、", style.permissions())
                        ))));
            } else {
                lore.add(LanguageBundle.render(menu.text(
                        "unlock-grant", "<gray>获取方式：<white>管理员限时授权")));
            }
            lore.add(LanguageBundle.render(menu.text(
                    "unlock-hint", "<dark_gray>可通过权限组、样式权限或限时授权解锁")));
            meta.lore(lore.stream().map(RepositoryGui::withoutItalics).toList());
        });
        return item;
    }

    private ItemStack adminStyleItem(
            NameStyle style,
            NameGrant grant,
            String playerName,
            MenuSettings.View menu,
            boolean active
    ) {
        Material material = Material.matchMaterial(style.icon());
        ItemStack item = new ItemStack(material == null ? Material.NAME_TAG : material);
        item.editMeta(meta -> {
            meta.displayName(withoutItalics(renderer.render(style.displayName(), style)));
            List<Component> lore = new ArrayList<>();
            lore.add(LanguageBundle.render(menu.text("style-preview", "<gray>预览："))
                    .append(renderer.render(playerName, style)));
            lore.add(LanguageBundle.render(interpolate(menu.text("style-type", "<dark_gray>类型：%type%"),
                    Map.of("type", styleTypeName(style)))));
            if (style.hasPermissionUnlock()) {
                lore.add(LanguageBundle.render(interpolate(menu.text("permission", "<dark_gray>权限解锁节点：%permission%"),
                        Map.of(
                                "permission", String.join("、", style.permissions()),
                                "permissions", String.join("、", style.permissions())
                        ))));
            } else {
                lore.add(LanguageBundle.render(menu.text("permission-none",
                        "<dark_gray>权限解锁：未配置（仅支持授权解锁）")));
            }
            if (grant == null) {
                lore.add(LanguageBundle.render(menu.text("grant-none", "<gray>单独授权：无")));
            } else {
                lore.add(LanguageBundle.render(interpolate(menu.text("grant-remaining",
                        "<yellow>单独授权剩余：%duration%"), Map.of("duration",
                        DurationParser.format(Duration.between(Instant.now(), grant.expiresAt()))))));
            }
            if (active) {
                lore.add(LanguageBundle.render(menu.text("state-active", "<aqua>当前选择")));
            }
            lore.add(Component.empty());
            lore.add(LanguageBundle.render(menu.text(grant == null ? "action-grant" : "action-extend",
                    grant == null ? "<green>左键：授予 7 天" : "<green>左键：续期 7 天")));
            lore.add(LanguageBundle.render(menu.text(grant == null ? "action-grant-long" : "action-extend-long",
                    grant == null ? "<yellow>Shift+左键：授予 30 天" : "<yellow>Shift+左键：续期 30 天")));
            if (grant != null) {
                lore.add(LanguageBundle.render(menu.text("action-revoke", "<red>右键：撤销")));
            }
            meta.lore(lore.stream().map(RepositoryGui::withoutItalics).toList());
            if (active) meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    private Inventory createInventory(
            RepositoryHolder holder,
            MenuSettings.View menu,
            Map<String, ?> values
    ) {
        Inventory inventory = Bukkit.createInventory(holder, menu.size(), withoutItalics(
                LanguageBundle.render(interpolate(menu.title(), values))));
        holder.inventory(inventory);
        return inventory;
    }

    private void navigation(
            Inventory inventory,
            MenuSettings.View menu,
            int page,
            int total,
            Map<String, ?> values
    ) {
        Map<String, Object> infoValues = new java.util.LinkedHashMap<>(values);
        infoValues.put("page", page + 1);
        String info = interpolate(menu.text("info", "%count% · 第 %page% 页"), infoValues);
        if (page > 0) placeButton(inventory, menu.button("previous"), Map.of());
        placeButton(inventory, menu.button("info"), Map.of("info", info));
        if ((page + 1) * menu.styleSlots().size() < total) {
            placeButton(inventory, menu.button("next"), Map.of());
        }
        placeButton(inventory, menu.button("close"), Map.of());
    }

    private ItemStack menuItem(MenuSettings.Button button, Map<String, ?> values) {
        ItemStack item = new ItemStack(button.material());
        item.editMeta(meta -> {
            meta.displayName(withoutItalics(
                    LanguageBundle.render(interpolate(button.name(), values))));
            if (!button.lore().isEmpty()) {
                meta.lore(button.lore().stream()
                        .map(line -> LanguageBundle.render(interpolate(line, values)))
                        .map(RepositoryGui::withoutItalics)
                        .toList());
            }
        });
        return item;
    }

    private void placePlayerPreview(
            Inventory inventory,
            MenuSettings.View menu,
            Player player,
            PlaceholderSnapshot snapshot,
            Optional<String> activeStyleKey,
            Instant now
    ) {
        MenuSettings.Button button = menu.button("preview");
        if (button.slot() < 0 || button.slot() >= inventory.getSize()) {
            return;
        }
        String style = activeStyleKey
                .flatMap(service::style)
                .map(activeStyle -> MINI_MESSAGE.serialize(
                        withoutItalics(renderer.render(activeStyle.displayName(), activeStyle))))
                .orElse(menu.text("preview-none", "<gray>未启用"));
        Component nickname = playerPreviewName(
                player.getName(), activeStyleKey, snapshot.renderedNameAt(now));
        ItemStack item = menuItem(button, Map.of(
                "nickname", MINI_MESSAGE.serialize(nickname),
                "player", player.getName(),
                "style", style
        ));
        item.editMeta(meta -> {
            if (meta instanceof SkullMeta skullMeta) {
                skullMeta.setPlayerProfile(player.getPlayerProfile());
            }
        });
        inventory.setItem(button.slot(), item);
    }

    private void placeButton(Inventory inventory, MenuSettings.Button button, Map<String, ?> values) {
        if (button != null && button.slot() >= 0 && button.slot() < inventory.getSize()) {
            inventory.setItem(button.slot(), menuItem(button, values));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof RepositoryHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        MenuSettings.View menu = holder.menu();
        if (slot == menu.button("close").slot()) {
            viewer.closeInventory();
            return;
        }
        if (slot == menu.button("previous").slot() && holder.page() > 0) {
            reopen(viewer, holder, holder.page() - 1);
            return;
        }
        if (slot == menu.button("next").slot()) {
            reopen(viewer, holder, holder.page() + 1);
            return;
        }
        if (isPlayerMode(holder.mode()) && slot == menu.button("home").slot()) {
            openPlayerView(viewer, RepositoryHolder.Mode.PLAYER_HOME, 0);
            return;
        }
        if (holder.mode() == RepositoryHolder.Mode.PLAYER_HOME) {
            if (slot == menu.button("unlocked").slot()) {
                openPlayerView(viewer, RepositoryHolder.Mode.PLAYER_UNLOCKED, 0);
                return;
            }
            if (slot == menu.button("locked").slot()) {
                openPlayerView(viewer, RepositoryHolder.Mode.PLAYER_LOCKED, 0);
                return;
            }
        }
        if (holder.mode() == RepositoryHolder.Mode.ADMIN && slot == menu.button("audit").slot()) {
            showAudit(viewer, holder);
            return;
        }
        if (isPlayerMode(holder.mode()) && slot == menu.button("disable").slot()) {
            UUID viewerId = viewer.getUniqueId();
            long requestToken = requestTokens.getOrDefault(viewerId, 0L);
            service.deactivate(viewerId).whenComplete((ignored, error) ->
                    tasks.scheduler().runForOnlinePlayer(viewerId, current -> {
                if (!isCurrentRequest(viewerId, requestToken)) {
                    return;
                }
                if (error != null) sendError(current, error);
                else {
                    display.reset(current);
                    current.sendMessage(language.get().component("gui.disabled"));
                    current.closeInventory();
                }
            }));
            return;
        }
        String styleKey = holder.stylesBySlot().get(slot);
        if (styleKey == null) return;
        if (holder.mode() == RepositoryHolder.Mode.PLAYER_UNLOCKED) {
            if (event.getClick().isLeftClick()) {
                activate(viewer, styleKey);
            }
        } else if (holder.mode() == RepositoryHolder.Mode.ADMIN
                && viewer.hasPermission("kucolorfulname.admin.gui")
                && (event.getClick().isLeftClick() || event.getClick().isRightClick())) {
            manage(viewer, holder, styleKey, event.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof RepositoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        requestTokens.remove(playerId);
        adminOperations.removeIf(key -> key.viewerId().equals(playerId));
    }

    private void activate(Player player, String styleKey) {
        UUID playerId = player.getUniqueId();
        long requestToken = requestTokens.getOrDefault(playerId, 0L);
        player.closeInventory();
        service.activate(player, styleKey).whenComplete((result, error) ->
                tasks.scheduler().runForOnlinePlayer(playerId, current -> {
            if (!isCurrentRequest(playerId, requestToken)) {
                return;
            }
            if (error != null) {
                sendError(current, error);
                return;
            }
            if (result == ActivationResult.ACTIVATED) {
                display.refresh(current);
                current.sendMessage(language.get().component("gui.activated"));
            } else {
                current.sendMessage(activationMessage(result));
            }
        }));
    }

    private void manage(Player viewer, RepositoryHolder holder, String styleKey, ClickType click) {
        UUID viewerId = viewer.getUniqueId();
        AdminOperationKey operation = new AdminOperationKey(
                viewerId, holder.targetId(), styleKey,
                requestTokens.getOrDefault(viewerId, 0L));
        if (!adminOperations.add(operation)) {
            return;
        }
        if (click.isRightClick()) {
            if (!viewer.hasPermission("kucolorfulname.admin.revoke")) {
                viewer.sendMessage(language.get().component("gui.no-revoke-permission"));
                adminOperations.remove(operation);
                return;
            }
            service.revoke(holder.targetId(), styleKey, viewerId, "admin-gui")
                    .whenComplete((removed, error) -> afterManage(viewerId, holder, operation, error,
                    Boolean.TRUE.equals(removed)
                            ? language.get().raw("gui.grant-revoked")
                            : language.get().raw("gui.grant-missing")));
            return;
        }
        Optional<NameGrant> existing = Optional.ofNullable(holder.grantsByStyle().get(styleKey));
        Duration duration = click.isShiftClick() ? Duration.ofDays(30) : Duration.ofDays(7);
        if (existing.isPresent()) {
            if (!viewer.hasPermission("kucolorfulname.admin.extend")) {
                viewer.sendMessage(language.get().component("gui.no-extend-permission"));
                adminOperations.remove(operation);
                return;
            }
            service.extend(holder.targetId(), styleKey, duration, viewerId, "admin-gui")
                    .whenComplete((grant, error) -> afterManage(viewerId, holder, operation, error,
                            grant != null && grant.isPresent()
                                    ? language.get().raw("gui.grant-extended", Map.of("days", duration.toDays()))
                                    : language.get().raw("gui.grant-missing")));
            return;
        }
        if (!viewer.hasPermission("kucolorfulname.admin.grant")) {
            viewer.sendMessage(language.get().component("gui.no-grant-permission"));
            adminOperations.remove(operation);
            return;
        }
        service.grant(holder.targetId(), styleKey, duration, viewerId, "admin-gui")
                .whenComplete((grant, error) -> afterManage(viewerId, holder, operation, error,
                        language.get().raw("gui.grant-issued", Map.of("days", duration.toDays()))));
    }

    private void showAudit(Player viewer, RepositoryHolder holder) {
        if (!viewer.hasPermission("kucolorfulname.admin.audit")) {
            viewer.sendMessage(language.get().component("gui.no-audit-permission"));
            return;
        }
        UUID viewerId = viewer.getUniqueId();
        viewer.closeInventory();
        service.audit(holder.targetId(), 10).whenComplete((entries, error) ->
                tasks.scheduler().runForOnlinePlayer(viewerId, current -> {
            if (error != null) {
                sendError(current, error);
                return;
            }
            current.sendMessage(language.get().component("gui.audit-header",
                    Map.of("player", holder.targetName())));
            if (entries.isEmpty()) {
                current.sendMessage(language.get().component("gui.audit-empty"));
                return;
            }
            entries.forEach(entry -> current.sendMessage(language.get().component("gui.audit-entry",
                    Map.of(
                            "time", AUDIT_TIME.format(entry.occurredAt()),
                            "action", entry.action(),
                            "style", entry.styleKey(),
                            "source", entry.source()))));
        }));
    }

    private void afterManage(
            UUID viewerId,
            RepositoryHolder holder,
            AdminOperationKey operation,
            Throwable error,
            String success
    ) {
        if (!adminOperations.contains(operation)) {
            return;
        }
        tasks.scheduler().runForOnlinePlayer(viewerId, current -> {
            if (!adminOperations.remove(operation)) {
                return;
            }
            if (error != null) sendError(current, error);
            else {
                current.sendMessage(LanguageBundle.render(success));
                tasks.scheduler().runForOnlinePlayer(holder.targetId(), display::refresh);
            }
            current.closeInventory();
            openAdmin(current, holder.targetId(), holder.targetName(), holder.page());
        }, () -> adminOperations.remove(operation));
    }

    private void reopen(Player viewer, RepositoryHolder holder, int page) {
        nextRequestToken(viewer.getUniqueId());
        if (holder.mode() == RepositoryHolder.Mode.ADMIN) {
            viewer.openInventory(buildAdmin(
                    holder.targetId(), holder.targetName(), page, holder.snapshot()));
        } else {
            openPlayerInventory(viewer, holder.mode(), page, menus.get());
        }
    }

    private void openPlayerView(Player viewer, RepositoryHolder.Mode mode, int page) {
        nextRequestToken(viewer.getUniqueId());
        openPlayerInventory(viewer, mode, page, menus.get());
    }

    private void openPlayerInventory(
            Player viewer,
            RepositoryHolder.Mode requestedMode,
            int page,
            MenuSettings settings
    ) {
        RepositoryHolder.Mode mode = settings.categorizedPlayerRepository()
                ? requestedMode
                : RepositoryHolder.Mode.PLAYER_UNLOCKED;
        Inventory inventory = mode == RepositoryHolder.Mode.PLAYER_HOME
                ? buildPlayerHome(viewer, settings)
                : buildPlayerList(viewer, mode, page, settings);
        viewer.openInventory(inventory);
    }

    private boolean isPlayerMode(RepositoryHolder.Mode mode) {
        return mode != RepositoryHolder.Mode.ADMIN;
    }

    private int validPage(int requested, int total, int pageSize) {
        int last = Math.max(0, (total - 1) / Math.max(1, pageSize));
        return Math.max(0, Math.min(requested, last));
    }

    private Component activationMessage(ActivationResult result) {
        return switch (result) {
            case UNKNOWN_STYLE -> language.get().component("gui.unknown-style");
            case NOT_UNLOCKED -> language.get().component("gui.unavailable");
            case CANCELLED -> language.get().component("gui.cancelled");
            case ACTIVATED -> language.get().component("gui.activated");
        };
    }

    private String styleTypeName(NameStyle style) {
        return switch (style.type()) {
            case SOLID -> language.get().raw("gui.type-solid");
            case GRADIENT -> language.get().raw("gui.type-gradient");
            case ANIMATED -> language.get().raw("gui.type-animated");
        };
    }

    private void sendError(Player player, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        player.sendMessage(language.get().component("gui.operation-failed",
                Map.of("error", String.valueOf(cause.getMessage()))));
    }

    private long nextRequestToken(UUID viewerId) {
        long token = requestSequence.incrementAndGet();
        requestTokens.put(viewerId, token);
        return token;
    }

    private boolean isCurrentRequest(UUID viewerId, long token) {
        return Long.valueOf(token).equals(requestTokens.get(viewerId));
    }

    private static String interpolate(String template, Map<String, ?> placeholders) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
        }
        return result;
    }

    static Component playerPreviewName(
            String playerName,
            Optional<String> activeStyleKey,
            Component renderedName
    ) {
        Component preview = activeStyleKey.isPresent()
                ? renderedName
                : Component.text(playerName, NamedTextColor.WHITE);
        return withoutItalics(preview);
    }

    static Component withoutItalics(Component component) {
        Component result = component.decoration(TextDecoration.ITALIC, false);
        HoverEvent<?> hoverEvent = result.hoverEvent();
        if (hoverEvent != null && hoverEvent.action() == HoverEvent.Action.SHOW_TEXT) {
            result = result.hoverEvent(HoverEvent.showText(
                    withoutItalics((Component) hoverEvent.value())));
        }
        return result.children(result.children().stream()
                .map(RepositoryGui::withoutItalics)
                .toList());
    }

    private record AdminOperationKey(UUID viewerId, UUID targetId, String styleKey, long requestId) { }
}
