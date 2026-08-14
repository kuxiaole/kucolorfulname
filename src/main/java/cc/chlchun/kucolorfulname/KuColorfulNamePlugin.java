package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.api.ColorfulNameApi;
import cc.chlchun.kucolorfulname.command.ColorfulNameCommand;
import cc.chlchun.kucolorfulname.config.ColorfulNameConfig;
import cc.chlchun.kucolorfulname.config.LanguageBundle;
import cc.chlchun.kucolorfulname.config.MenuSettings;
import cc.chlchun.kucolorfulname.config.StorageSettings;
import cc.chlchun.kucolorfulname.config.StorageType;
import cc.chlchun.kucolorfulname.gui.RepositoryGui;
import cc.chlchun.kucolorfulname.hook.KuColorfulNameExpansion;
import cc.chlchun.kucolorfulname.listener.PlayerLifecycleListener;
import cc.chlchun.kucolorfulname.platform.DisplayManager;
import cc.chlchun.kucolorfulname.platform.AnimationManager;
import cc.chlchun.kucolorfulname.platform.OnlinePlayerIndex;
import cc.chlchun.kucolorfulname.platform.PlayerTasks;
import cc.chlchun.kucolorfulname.platform.TaskHandle;
import cc.chlchun.kucolorfulname.platform.TaskScheduler;
import cc.chlchun.kucolorfulname.render.NameRenderer;
import cc.chlchun.kucolorfulname.service.ColorfulNameService;
import cc.chlchun.kucolorfulname.storage.NameRepository;
import cc.chlchun.kucolorfulname.storage.MariaDbNameRepository;
import cc.chlchun.kucolorfulname.storage.MySqlNameRepository;
import cc.chlchun.kucolorfulname.storage.SqliteNameRepository;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.io.File;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

public final class KuColorfulNamePlugin extends JavaPlugin {
    private volatile ColorfulNameConfig colorfulConfig;
    private volatile LanguageBundle language;
    private volatile MenuSettings menus;
    private NameRepository repository;
    private ColorfulNameService service;
    private DisplayManager displayManager;
    private AnimationManager animationManager;
    private PlayerTasks playerTasks;
    private TaskScheduler scheduler;
    private final OnlinePlayerIndex onlinePlayers = new OnlinePlayerIndex();
    private KuColorfulNameExpansion placeholderExpansion;
    private TaskHandle expiryTask;
    private TaskHandle synchronizationTask;
    private final AtomicReference<RunToken> expiryRunning = new AtomicReference<>();
    private final AtomicReference<RunToken> synchronizationRunning = new AtomicReference<>();
    private final AtomicLong expiryGeneration = new AtomicLong();
    private final AtomicLong synchronizationGeneration = new AtomicLong();
    private StorageSettings runningStorage;
    private volatile ColorfulNameCommand commandHandler;

    @Override
    public void onLoad() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        "kucolorfulname",
                        "管理玩家颜色昵称",
                        List.of("kcn", "colorname"),
                        new BasicCommand() {
                            @Override
                            public void execute(CommandSourceStack source, String[] args) {
                                ColorfulNameCommand handler = commandHandler;
                                if (handler != null) {
                                    handler.execute(source.getSender(), "kucolorfulname", args);
                                }
                            }

                            @Override
                            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                                ColorfulNameCommand handler = commandHandler;
                                return handler == null
                                        ? List.of()
                                        : handler.suggest(source.getSender(), args);
                            }

                            @Override
                            public String permission() {
                                return "kucolorfulname.use";
                            }
                        }));
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            reloadPluginConfig();
            repository = createRepository(colorfulConfig.storage());
            runningStorage = colorfulConfig.storage();
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "kucolorfulname initialization failed", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        playerTasks = new PlayerTasks(this);
        scheduler = playerTasks.scheduler();
        NameRenderer renderer = new NameRenderer();
        service = new ColorfulNameService(repository, this::colorfulConfig, renderer, this, playerTasks);
        animationManager = new AnimationManager(
                service,
                this::colorfulConfig,
                renderer,
                scheduler,
                onlinePlayers,
                getLogger(),
                getServer().getMinecraftVersion()
        );
        displayManager = new DisplayManager(
                service, this::colorfulConfig, playerTasks, animationManager);
        RepositoryGui repositoryGui = new RepositoryGui(
                service,
                this::colorfulConfig,
                renderer,
                displayManager,
                playerTasks,
                this::language,
                this::menus
        );

        getServer().getServicesManager().register(ColorfulNameApi.class, service, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(repositoryGui, this);
        getServer().getPluginManager().registerEvents(
                new PlayerLifecycleListener(
                        service, displayManager, animationManager, playerTasks,
                        onlinePlayers, getLogger(), this::isEnabled),
                this
        );

        commandHandler = new ColorfulNameCommand(
                this,
                service,
                repositoryGui,
                displayManager,
                playerTasks,
                onlinePlayers,
                this::language
        );

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion = new KuColorfulNameExpansion(this, service, onlinePlayers);
            if (placeholderExpansion.register()) {
                getLogger().info("PlaceholderAPI expansion registered.");
            } else {
                placeholderExpansion = null;
                getLogger().warning("PlaceholderAPI expansion registration failed.");
            }
        }

        TaskScheduler startupScheduler = scheduler;
        ColorfulNameService startupService = service;
        DisplayManager startupDisplay = displayManager;
        startupScheduler.runForEachOnlinePlayer(player -> {
            UUID playerId = player.getUniqueId();
            onlinePlayers.add(playerId, player.getName());
            startupService.load(playerId).whenComplete((ignored, error) -> {
                if (!isEnabled()) {
                    return;
                }
                if (error != null) {
                    getLogger().log(Level.SEVERE, "Failed to load player " + playerId, error);
                } else {
                    startupScheduler.runForOnlinePlayer(playerId, current -> {
                        if (startupService.isCached(playerId)) {
                            startupDisplay.refresh(current);
                        }
                    });
                }
            });
        });
        scheduleExpiryTask();
        scheduleSynchronizationTask();
        animationManager.start();
        getLogger().info("kucolorfulname enabled with " + colorfulConfig.styles().size()
                + " styles and " + runningStorage.type() + " storage.");
    }

    @Override
    public void onDisable() {
        commandHandler = null;
        expiryGeneration.incrementAndGet();
        synchronizationGeneration.incrementAndGet();
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
        if (expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }
        if (synchronizationTask != null) {
            synchronizationTask.cancel();
            synchronizationTask = null;
        }
        expiryRunning.set(null);
        synchronizationRunning.set(null);
        if (displayManager != null) {
            displayManager.close();
        }
        if (animationManager != null) {
            animationManager.close();
        }
        if (service != null) {
            service.close();
        }
        onlinePlayers.clear();
        getServer().getServicesManager().unregisterAll(this);
        if (repository != null) {
            repository.close();
            repository = null;
        }
        service = null;
        displayManager = null;
        animationManager = null;
        playerTasks = null;
        scheduler = null;
        runningStorage = null;
        language = null;
        menus = null;
    }

    public void reloadPluginConfig() {
        ColorfulNameConfig previous = this.colorfulConfig;
        reloadConfig();
        ColorfulNameConfig loaded = ColorfulNameConfig.load(getConfig());
        LanguageBundle loadedLanguage = loadLanguage();
        MenuSettings loadedMenus = loadMenus();
        if (repository != null && runningStorage != null && !runningStorage.equals(loaded.storage())) {
            throw new IllegalArgumentException("存储类型或连接配置已修改，请重启服务器后生效");
        }
        this.colorfulConfig = loaded;
        this.language = loadedLanguage;
        this.menus = loadedMenus;
        if (isEnabled() && service != null) {
            if (displayManager != null
                    && previous != null
                    && previous.settings().applyToTabList()
                    && !loaded.settings().applyToTabList()) {
                displayManager.resetTabNamesOnNextRefresh(onlinePlayers.snapshotIds());
            }
            service.configurationReloaded();
            scheduleExpiryTask();
            scheduleSynchronizationTask();
            if (animationManager != null) {
                animationManager.reconfigure();
            }
        }
    }

    public ColorfulNameConfig colorfulConfig() {
        return Objects.requireNonNull(colorfulConfig, "Plugin configuration is not loaded");
    }

    public LanguageBundle language() {
        return Objects.requireNonNull(language, "Language is not loaded");
    }

    public MenuSettings menus() {
        return Objects.requireNonNull(menus, "Menus are not loaded");
    }

    private LanguageBundle loadLanguage() {
        File file = ensureResourceFile("lang.yml");
        return LanguageBundle.load(YamlConfiguration.loadConfiguration(file));
    }

    private MenuSettings loadMenus() {
        File file = ensureResourceFile("menus.yml");
        return MenuSettings.load(YamlConfiguration.loadConfiguration(file));
    }

    private File ensureResourceFile(String resource) {
        File file = new File(getDataFolder(), resource);
        if (!file.isFile()) {
            saveResource(resource, false);
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("Unable to create editable resource: " + resource);
        }
        return file;
    }

    private void scheduleExpiryTask() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        long generation = expiryGeneration.incrementAndGet();
        long periodTicks = colorfulConfig.settings().expiryCheckSeconds() * 20L;
        expiryTask = scheduler.runGlobalAtFixedRate(
                () -> removeExpired(generation), periodTicks, periodTicks);
    }

    private void removeExpired(long generation) {
        ColorfulNameService currentService = service;
        DisplayManager currentDisplay = displayManager;
        if (generation != expiryGeneration.get()
                || currentService == null
                || currentDisplay == null) {
            return;
        }
        RunToken token = new RunToken(generation);
        if (!expiryRunning.compareAndSet(null, token)) {
            return;
        }
        currentService.removeExpired().whenComplete((removed, error) -> {
            if (generation != expiryGeneration.get() || !isEnabled()) {
                expiryRunning.compareAndSet(token, null);
                return;
            }
            if (error != null) {
                expiryRunning.compareAndSet(token, null);
                getLogger().log(Level.SEVERE, "Failed to remove expired colorful names", error);
                return;
            }
            if (removed > 0) {
                getLogger().log(Level.FINE, "Removed {0} expired colorful name grants", removed);
            }
            List<UUID> playerIds = List.copyOf(onlinePlayers.snapshotIds());
            if (playerIds.isEmpty()) {
                expiryRunning.compareAndSet(token, null);
                return;
            }
            long batchDelayTicks = periodicBatchDelayTicks(
                    playerIds.size(), colorfulConfig.settings().expiryCheckSeconds() * 20L);
            scheduler.runGlobal(() -> refreshPlayerBatch(
                            playerIds, 0, batchDelayTicks, generation, token, currentDisplay),
                    () -> expiryRunning.compareAndSet(token, null));
        });
    }

    private NameRepository createRepository(StorageSettings storage) throws SQLException, IOException {
        return switch (storage.type()) {
            case SQLITE -> new SqliteNameRepository(getDataFolder().toPath());
            case MYSQL -> new MySqlNameRepository(storage.mysql());
            case MARIADB -> new MariaDbNameRepository(storage.mariadb());
        };
    }

    private void scheduleSynchronizationTask() {
        if (synchronizationTask != null) {
            synchronizationTask.cancel();
            synchronizationTask = null;
        }
        long generation = synchronizationGeneration.incrementAndGet();
        if (runningStorage == null
                || (runningStorage.type() != StorageType.MYSQL
                && runningStorage.type() != StorageType.MARIADB)) {
            return;
        }
        int intervalSeconds = runningStorage.type() == StorageType.MARIADB
                ? runningStorage.mariadb().synchronizationIntervalSeconds()
                : runningStorage.mysql().synchronizationIntervalSeconds();
        if (intervalSeconds == 0) {
            return;
        }
        long periodTicks = intervalSeconds * 20L;
        long initialDelayTicks = Math.max(1L, periodTicks / 2L);
        synchronizationTask = scheduler.runGlobalAtFixedRate(
                () -> synchronizeOnlinePlayers(generation), initialDelayTicks, periodTicks);
    }

    private void synchronizeOnlinePlayers(long generation) {
        ColorfulNameService currentService = service;
        DisplayManager currentDisplay = displayManager;
        if (generation != synchronizationGeneration.get()
                || currentService == null
                || currentDisplay == null) {
            return;
        }
        RunToken token = new RunToken(generation);
        if (!synchronizationRunning.compareAndSet(null, token)) {
            return;
        }
        List<UUID> playerIds = List.copyOf(onlinePlayers.snapshotIds());
        if (playerIds.isEmpty()) {
            synchronizationRunning.compareAndSet(token, null);
            return;
        }
        long intervalTicks = synchronizationIntervalTicks();
        long batchDelayTicks = periodicBatchDelayTicks(playerIds.size(), intervalTicks);
        synchronizePlayerBatch(
                playerIds, 0, batchDelayTicks, generation, token, currentService, currentDisplay);
    }

    private void synchronizePlayerBatch(
            List<UUID> playerIds,
            int offset,
            long batchDelayTicks,
            long generation,
            RunToken token,
            ColorfulNameService currentService,
            DisplayManager currentDisplay
    ) {
        if (generation != synchronizationGeneration.get() || !isEnabled()
                || service != currentService || displayManager != currentDisplay) {
            synchronizationRunning.compareAndSet(token, null);
            return;
        }
        if (offset >= playerIds.size()) {
            synchronizationRunning.compareAndSet(token, null);
            return;
        }

        UUID playerId = playerIds.get(offset);
        currentService.synchronize(playerId).whenComplete((changed, error) -> {
            if (generation != synchronizationGeneration.get() || !isEnabled()) {
                synchronizationRunning.compareAndSet(token, null);
                return;
            }
            if (error != null) {
                getLogger().log(Level.WARNING,
                        "Failed to synchronize colorful name for " + playerId, error);
            } else if (Boolean.TRUE.equals(changed)) {
                scheduler.runForOnlinePlayer(playerId, currentDisplay::refresh);
            }
            int nextOffset = offset + 1;
            if (nextOffset >= playerIds.size()) {
                synchronizationRunning.compareAndSet(token, null);
                return;
            }
            scheduler.runGlobalDelayed(
                    () -> synchronizePlayerBatch(
                            playerIds, nextOffset, batchDelayTicks, generation, token,
                            currentService, currentDisplay),
                    batchDelayTicks,
                    () -> synchronizationRunning.compareAndSet(token, null)
            );
        });
    }

    private long synchronizationIntervalTicks() {
        if (runningStorage == null) {
            return 1L;
        }
        int seconds = runningStorage.type() == StorageType.MARIADB
                ? runningStorage.mariadb().synchronizationIntervalSeconds()
                : runningStorage.mysql().synchronizationIntervalSeconds();
        return Math.max(1L, seconds * 20L);
    }

    private void refreshPlayerBatch(
            List<UUID> playerIds,
            int offset,
            long batchDelayTicks,
            long generation,
            RunToken token,
            DisplayManager currentDisplay
    ) {
        if (generation != expiryGeneration.get() || !isEnabled()
                || displayManager != currentDisplay) {
            expiryRunning.compareAndSet(token, null);
            return;
        }
        if (offset >= playerIds.size()) {
            expiryRunning.compareAndSet(token, null);
            return;
        }
        scheduler.runForOnlinePlayer(playerIds.get(offset), currentDisplay::refresh);
        int nextOffset = offset + 1;
        if (nextOffset >= playerIds.size()) {
            expiryRunning.compareAndSet(token, null);
            return;
        }
        scheduler.runGlobalDelayed(
                () -> refreshPlayerBatch(
                        playerIds, nextOffset, batchDelayTicks, generation, token, currentDisplay),
                batchDelayTicks,
                () -> expiryRunning.compareAndSet(token, null)
        );
    }

    static long periodicBatchDelayTicks(int playerCount, long periodTicks) {
        if (playerCount < 1) {
            return 1L;
        }
        return Math.max(1L, periodTicks / playerCount);
    }

    private record RunToken(long generation) { }
}
