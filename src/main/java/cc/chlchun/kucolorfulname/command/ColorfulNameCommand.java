package cc.chlchun.kucolorfulname.command;

import cc.chlchun.kucolorfulname.KuColorfulNamePlugin;
import cc.chlchun.kucolorfulname.gui.RepositoryGui;
import cc.chlchun.kucolorfulname.api.GrantAuditEntry;
import cc.chlchun.kucolorfulname.config.LanguageBundle;
import cc.chlchun.kucolorfulname.platform.DisplayManager;
import cc.chlchun.kucolorfulname.platform.OnlinePlayerIndex;
import cc.chlchun.kucolorfulname.platform.PlayerTasks;
import cc.chlchun.kucolorfulname.platform.TaskScheduler.SenderContext;
import cc.chlchun.kucolorfulname.service.ColorfulNameService;
import cc.chlchun.kucolorfulname.util.DurationParser;
import cc.chlchun.kucolorfulname.util.PlayerResolver;
import cc.chlchun.kucolorfulname.util.PlayerResolver.PlayerTarget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ColorfulNameCommand implements TabExecutor {
    private static final List<String> GRANT_DURATIONS = List.of(
            "30s", "5m", "30m", "1h", "12h", "1d", "7d", "30d", "4w"
    );
    private static final List<String> EXTEND_DURATIONS = List.of(
            "1h", "12h", "1d", "7d", "30d", "4w"
    );
    private static final List<String> AUDIT_LIMITS = List.of("5", "10", "20", "50");
    private final KuColorfulNamePlugin plugin;
    private final ColorfulNameService service;
    private final RepositoryGui gui;
    private final DisplayManager display;
    private final PlayerTasks tasks;
    private final OnlinePlayerIndex onlinePlayers;
    private final Supplier<LanguageBundle> language;

    public ColorfulNameCommand(
            KuColorfulNamePlugin plugin,
            ColorfulNameService service,
            RepositoryGui gui,
            DisplayManager display,
            PlayerTasks tasks,
            OnlinePlayerIndex onlinePlayers,
            Supplier<LanguageBundle> language
    ) {
        this.plugin = plugin;
        this.service = service;
        this.gui = gui;
        this.display = display;
        this.tasks = tasks;
        this.onlinePlayers = onlinePlayers;
        this.language = language;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return execute(sender, label, args);
    }

    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) gui.openPlayer(player);
            else usage(sender, label);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "off" -> deactivate(sender);
            case "admin" -> openAdmin(sender, args);
            case "grant" -> grant(sender, args);
            case "extend" -> extend(sender, args);
            case "revoke" -> revoke(sender, args);
            case "audit" -> audit(sender, args);
            case "reload" -> reload(sender);
            default -> {
                usage(sender, label);
                yield true;
            }
        };
    }

    private boolean deactivate(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            error(sender, lang("player-only"));
            return true;
        }
        UUID playerId = player.getUniqueId();
        service.deactivate(playerId).whenComplete((ignored, failure) ->
                tasks.scheduler().runForOnlinePlayer(playerId, current -> {
            if (failure != null) sendFailure(current, failure);
            else {
                display.reset(current);
                success(current, lang("deactivated"));
            }
        }));
        return true;
    }

    private boolean openAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kucolorfulname.admin.gui")) {
            error(sender, lang("no-admin-gui"));
            return true;
        }
        if (!(sender instanceof Player viewer)) {
            error(sender, lang("admin-player-only"));
            return true;
        }
        if (args.length != 2) {
            error(sender, lang("usage-admin", Map.of("label", "kcn")));
            return true;
        }
        Optional<PlayerTarget> target = PlayerResolver.resolve(args[1], onlinePlayers);
        if (target.isEmpty()) {
            error(sender, lang("player-not-found"));
            return true;
        }
        gui.openAdmin(viewer, target.get());
        return true;
    }

    private boolean grant(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kucolorfulname.admin.grant")) {
            error(sender, lang("no-grant-permission"));
            return true;
        }
        if (args.length != 4) {
            error(sender, lang("usage-grant", Map.of("label", "kcn")));
            return true;
        }
        Optional<PlayerTarget> target = PlayerResolver.resolve(args[1], onlinePlayers);
        if (target.isEmpty()) {
            error(sender, lang("player-not-found"));
            return true;
        }
        if (service.style(args[2]).isEmpty()) {
            error(sender, lang("unknown-style", Map.of("style", args[2])));
            return true;
        }
        Optional<Duration> duration = DurationParser.parse(args[3]);
        if (duration.isEmpty()) {
            error(sender, lang("invalid-grant-duration"));
            return true;
        }
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        SenderContext senderContext = tasks.scheduler().captureSender(sender);
        PlayerTarget resolved = target.get();
        service.grant(resolved.playerId(), args[2], duration.get(), actor, "admin-command")
                .whenComplete((saved, failure) -> runForSender(senderContext, () -> {
                    if (failure != null) sendFailure(sender, failure);
                    else {
                        success(sender, lang("grant-issued", Map.of(
                                "player", resolved.label(),
                                "style", saved.styleKey(),
                                "duration", DurationParser.format(duration.get()))));
                        refreshOnlineTarget(resolved.playerId());
                    }
                }));
        return true;
    }

    private boolean revoke(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kucolorfulname.admin.revoke")) {
            error(sender, lang("no-revoke-permission"));
            return true;
        }
        if (args.length != 3) {
            error(sender, lang("usage-revoke", Map.of("label", "kcn")));
            return true;
        }
        Optional<PlayerTarget> target = PlayerResolver.resolve(args[1], onlinePlayers);
        if (target.isEmpty()) {
            error(sender, lang("player-not-found"));
            return true;
        }
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        SenderContext senderContext = tasks.scheduler().captureSender(sender);
        PlayerTarget resolved = target.get();
        service.revoke(resolved.playerId(), args[2], actor, "admin-command").whenComplete((removed, failure) ->
                runForSender(senderContext, () -> {
                    if (failure != null) sendFailure(sender, failure);
                    else if (removed) success(sender, lang("grant-revoked", Map.of(
                            "player", resolved.label(), "style", args[2])));
                    else error(sender, lang("no-grant"));
                    refreshOnlineTarget(resolved.playerId());
                }));
        return true;
    }

    private boolean extend(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kucolorfulname.admin.extend")) {
            error(sender, lang("no-extend-permission"));
            return true;
        }
        if (args.length != 4) {
            error(sender, lang("usage-extend", Map.of("label", "kcn")));
            return true;
        }
        Optional<PlayerTarget> target = PlayerResolver.resolve(args[1], onlinePlayers);
        Optional<Duration> duration = DurationParser.parse(args[3]);
        if (target.isEmpty()) {
            error(sender, lang("player-not-found"));
            return true;
        }
        if (duration.isEmpty()) {
            error(sender, lang("invalid-extend-duration"));
            return true;
        }
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        SenderContext senderContext = tasks.scheduler().captureSender(sender);
        PlayerTarget resolved = target.get();
        service.extend(resolved.playerId(), args[2], duration.get(), actor, "admin-command")
                .whenComplete((extended, failure) -> runForSender(senderContext, () -> {
                    if (failure != null) sendFailure(sender, failure);
                    else if (extended.isEmpty()) error(sender, lang("grant-missing"));
                    else {
                        success(sender, lang("grant-extended", Map.of(
                                "player", resolved.label(), "style", args[2],
                                "duration", DurationParser.format(duration.get()))));
                        refreshOnlineTarget(resolved.playerId());
                    }
                }));
        return true;
    }

    private boolean audit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kucolorfulname.admin.audit")) {
            error(sender, lang("no-audit-permission"));
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            error(sender, lang("usage-audit", Map.of("label", "kcn")));
            return true;
        }
        Optional<PlayerTarget> target = PlayerResolver.resolve(args[1], onlinePlayers);
        if (target.isEmpty()) {
            error(sender, lang("player-not-found"));
            return true;
        }
        int limit = 10;
        if (args.length == 3) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[2])));
            } catch (NumberFormatException exception) {
                error(sender, lang("invalid-audit-limit"));
                return true;
            }
        }
        PlayerTarget resolved = target.get();
        SenderContext senderContext = tasks.scheduler().captureSender(sender);
        service.audit(resolved.playerId(), limit).whenComplete((entries, failure) ->
                runForSender(senderContext, () -> {
                    if (failure != null) {
                        sendFailure(sender, failure);
                        return;
                    }
                    sender.sendMessage(LanguageBundle.render(lang("audit-header",
                            Map.of("player", resolved.label()))));
                    if (entries.isEmpty()) {
                        sender.sendMessage(LanguageBundle.render(lang("audit-empty")));
                        return;
                    }
                    for (GrantAuditEntry entry : entries) {
                        String actorName = entry.actorId() == null
                                ? lang("audit-system") : entry.actorId().toString();
                        sender.sendMessage(LanguageBundle.render(lang("audit-entry", Map.of(
                                "time", entry.occurredAt(), "action", entry.action(),
                                "style", entry.styleKey(), "actor", actorName,
                                "source", entry.source()))));
                    }
                }));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("kucolorfulname.admin.reload")) {
            error(sender, lang("no-reload-permission"));
            return true;
        }
        SenderContext senderContext = tasks.scheduler().captureSender(sender);
        tasks.scheduler().runGlobal(() -> {
            try {
                plugin.reloadPluginConfig();
                tasks.scheduler().runForEachOnlinePlayer(onlinePlayers, display::refresh);
                runForSender(senderContext, () -> success(sender, lang("reloaded")));
            } catch (IllegalArgumentException exception) {
                runForSender(senderContext, () -> error(sender, lang("reload-failed",
                        Map.of("error", String.valueOf(exception.getMessage())))));
            }
        }, () -> runForSender(senderContext, () -> error(sender, lang("reload-closing"))));
        return true;
    }

    private void usage(CommandSender sender, String label) {
        sender.sendMessage(LanguageBundle.render(lang("usage-open", Map.of("label", label))));
        sender.sendMessage(LanguageBundle.render(lang("usage-off", Map.of("label", label))));
        if (hasAnyAdminPermission(sender)) {
            sender.sendMessage(LanguageBundle.render(lang("usage-admin-line", Map.of("label", label))));
            sender.sendMessage(LanguageBundle.render(lang("usage-grant-line", Map.of("label", label))));
            sender.sendMessage(LanguageBundle.render(lang("usage-extend-line", Map.of("label", label))));
            sender.sendMessage(LanguageBundle.render(lang("usage-revoke-line", Map.of("label", label))));
            sender.sendMessage(LanguageBundle.render(lang("usage-audit-line", Map.of("label", label))));
            sender.sendMessage(LanguageBundle.render(lang("usage-reload-line", Map.of("label", label))));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        return suggest(sender, args);
    }

    public List<String> suggest(CommandSender sender, String[] args) {
        if (args.length == 0) return rootSuggestions(sender);
        if (args.length > 4) return List.of();
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) return filter(args[0], rootSuggestions(sender));

        if (args.length == 2) {
            if (Set.of("admin", "grant", "extend", "revoke", "audit").contains(subcommand)
                    && canUseSubcommand(sender, subcommand)) {
                return filter(args[1], playerSuggestions());
            }
            return List.of();
        }

        if (args.length == 3) {
            if (Set.of("grant", "extend", "revoke").contains(subcommand)
                    && canUseSubcommand(sender, subcommand)) {
                return filter(args[2], styleSuggestions(subcommand, args[1]));
            }
            if (subcommand.equals("audit") && canUseSubcommand(sender, subcommand)) {
                return filter(args[2], AUDIT_LIMITS);
            }
            return List.of();
        }

        if (subcommand.equals("grant") && canUseSubcommand(sender, subcommand)) {
            return filter(args[3], GRANT_DURATIONS);
        }
        if (subcommand.equals("extend") && canUseSubcommand(sender, subcommand)) {
            return filter(args[3], EXTEND_DURATIONS);
        }
        return List.of();
    }

    private List<String> filter(String prefix, Collection<String> values) {
        String normalized = prefix == null ? "" : prefix.trim().toLowerCase(Locale.ROOT);
        return new LinkedHashSet<>(values).stream()
                .filter(value -> value != null && value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }

    private List<String> rootSuggestions(CommandSender sender) {
        List<String> values = new ArrayList<>();
        if (sender instanceof Player) values.add("off");
        if (sender instanceof Player && sender.hasPermission("kucolorfulname.admin.gui")) {
            values.add("admin");
        }
        if (sender.hasPermission("kucolorfulname.admin.grant")) values.add("grant");
        if (sender.hasPermission("kucolorfulname.admin.extend")) values.add("extend");
        if (sender.hasPermission("kucolorfulname.admin.revoke")) values.add("revoke");
        if (sender.hasPermission("kucolorfulname.admin.audit")) values.add("audit");
        if (sender.hasPermission("kucolorfulname.admin.reload")) values.add("reload");
        return values;
    }

    private boolean canUseSubcommand(CommandSender sender, String subcommand) {
        return switch (subcommand) {
            case "admin" -> sender instanceof Player
                    && sender.hasPermission("kucolorfulname.admin.gui");
            case "grant" -> sender.hasPermission("kucolorfulname.admin.grant");
            case "extend" -> sender.hasPermission("kucolorfulname.admin.extend");
            case "revoke" -> sender.hasPermission("kucolorfulname.admin.revoke");
            case "audit" -> sender.hasPermission("kucolorfulname.admin.audit");
            default -> false;
        };
    }

    private List<String> playerSuggestions() {
        return onlinePlayers.snapshotNames();
    }

    private List<String> styleSuggestions(String subcommand, String targetInput) {
        UUID targetId = onlineTargetId(targetInput).orElse(null);
        if (targetId != null && service.isCached(targetId)) {
            if (subcommand.equals("extend")) {
                return service.cachedGrants(targetId).stream()
                        .map(grant -> grant.styleKey())
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
            }
            if (subcommand.equals("revoke")) {
                return service.cachedGrants(targetId).stream()
                        .map(grant -> grant.styleKey())
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
            }
        }
        return service.styles().stream()
                .map(style -> style.key())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private Optional<UUID> onlineTargetId(String input) {
        Optional<UUID> byName = onlinePlayers.findId(input);
        if (byName.isPresent()) {
            return byName;
        }
        try {
            UUID playerId = UUID.fromString(input);
            return onlinePlayers.contains(playerId) ? Optional.of(playerId) : Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private void success(CommandSender sender, String message) {
        sender.sendMessage(LanguageBundle.render(message));
    }

    private boolean hasAnyAdminPermission(CommandSender sender) {
        return sender.hasPermission("kucolorfulname.admin.gui")
                || sender.hasPermission("kucolorfulname.admin.grant")
                || sender.hasPermission("kucolorfulname.admin.extend")
                || sender.hasPermission("kucolorfulname.admin.revoke")
                || sender.hasPermission("kucolorfulname.admin.audit")
                || sender.hasPermission("kucolorfulname.admin.reload");
    }

    private void error(CommandSender sender, String message) {
        sender.sendMessage(LanguageBundle.render(message));
    }

    private void sendFailure(CommandSender sender, Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        error(sender, lang("operation-failed", Map.of("error", String.valueOf(cause.getMessage()))));
    }

    private void runForSender(SenderContext senderContext, Runnable task) {
        tasks.scheduler().runForSender(senderContext, task);
    }

    private void refreshOnlineTarget(UUID playerId) {
        tasks.scheduler().runForOnlinePlayer(playerId, display::refresh);
    }

    private String lang(String key) {
        return language.get().raw("command." + key);
    }

    private String lang(String key, Map<String, ?> placeholders) {
        return language.get().raw("command." + key, placeholders);
    }
}
