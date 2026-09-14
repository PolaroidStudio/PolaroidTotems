package me.juancayc.polaroidtotems;

import me.juancayc.polaroidtotems.commands.CommandRegistrar;
import me.juancayc.polaroidtotems.config.ConfigManager;
import me.juancayc.polaroidtotems.config.TotemsConfig;
import me.juancayc.polaroidtotems.item.ItemManager;
import me.juancayc.polaroidtotems.item.TotemStamper;
import me.juancayc.polaroidtotems.item.hooks.NexoItemHook;
import me.juancayc.polaroidtotems.item.hooks.VanillaItemHook;
import me.juancayc.polaroidtotems.listeners.CooldownListener;
import me.juancayc.polaroidtotems.listeners.NormalizationListener;
import me.juancayc.polaroidtotems.listeners.ResurrectListener;
import me.juancayc.polaroidtotems.messaging.ColorFormats;
import me.juancayc.polaroidtotems.messaging.MessageService;
import me.juancayc.polaroidtotems.messaging.MiniMessageProvider;
import me.juancayc.polaroidtotems.skill.MythicSkillHook;
import me.juancayc.polaroidtotems.skill.SkillValidationReporter;
import me.juancayc.polaroidtotems.skill.SkillValidator;
import me.juancayc.polaroidtotems.storage.CooldownRepository;
import me.juancayc.polaroidtotems.storage.MemoryCooldownRepository;
import me.juancayc.polaroidtotems.storage.SqlCooldownRepository;
import me.juancayc.polaroidtotems.storage.StorageException;
import me.juancayc.polaroidtotems.storage.StorageSettings;
import me.juancayc.polaroidtotems.totem.CooldownCache;
import me.juancayc.polaroidtotems.totem.CooldownService;
import me.juancayc.polaroidtotems.totem.TotemRegistry;
import me.juancayc.polaroidtotems.totem.TotemService;
import me.juancayc.polaroidtotems.util.SoundService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/** Plugin entry point. Wires the layers together and owns the enable/disable lifecycle. */
public final class PolaroidTotemsPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private TotemsConfig totemsConfig;
    private MessageService messageService;
    private TotemService totemService;
    private SkillValidationReporter skillValidationReporter;

    /**
     * The cooldown feature. Never null, even when the database could not be opened — see
     * {@link #openRepository}, which degrades to an in-memory store rather than to nothing.
     */
    private CooldownService cooldownService;

    @Override
    public void onEnable() {
        // One shared MiniMessage instance everywhere. No Nexo glyph resolver is registered — Nexo
        // puts its <glyph:id> tags in Paper's bootstrap tag registry, so they already resolve.
        ColorFormats.init(MiniMessageProvider.get());

        this.configManager = new ConfigManager(this);

        this.messageService = new MessageService(this, MiniMessageProvider.get());
        this.messageService.reload(configManager.language());

        this.totemsConfig = new TotemsConfig(this);
        this.totemsConfig.reload();

        ItemManager itemManager = new ItemManager();
        itemManager.registerHook(new VanillaItemHook());
        // The Nexo hook is registered unconditionally; it guards its own calls with
        // isPluginEnabled, and join-classpath: true in paper-plugin.yml makes the class resolvable.
        itemManager.registerHook(new NexoItemHook());

        TotemStamper stamper = new TotemStamper(this, itemManager, messageService);
        SoundService soundService = new SoundService(configManager);

        // Constructed unconditionally, exactly like the Nexo hook above: it guards its own calls
        // with isPluginEnabled, and join-classpath: true in paper-plugin.yml makes the class
        // resolvable. Building it here rather than behind an `if` keeps a server that installs
        // MythicMobs later from needing this plugin rebuilt or reordered — only a restart.
        MythicSkillHook skillHook = new MythicSkillHook(this);

        // Storage is opened ONCE, here, and never again. The schema runs once too. Neither is
        // reachable from reloadEverything() — see the comment there.
        this.cooldownService = new CooldownService(this, new CooldownCache(), openRepository());
        this.cooldownService.startTimers();
        // Only does anything on a PlugMan-style re-enable, where players are already connected and
        // no join event will ever fire for them.
        this.cooldownService.loadOnlinePlayers();

        this.totemService = new TotemService(this, configManager, totemsConfig, stamper,
                messageService, soundService, skillHook, cooldownService);

        getServer().getPluginManager().registerEvents(
                new ResurrectListener(configManager, totemService), this);
        getServer().getPluginManager().registerEvents(
                new NormalizationListener(configManager, totemsConfig, stamper), this);
        getServer().getPluginManager().registerEvents(
                new CooldownListener(cooldownService), this);

        new CommandRegistrar(this, messageService, stamper).register();

        this.skillValidationReporter = new SkillValidationReporter(
                this, new SkillValidator(skillHook), messageService);

        // DEFERRED, never inline. MythicMobs parses its skill files asynchronously and finishes
        // after its own onEnable returns, so validating here would query an empty registry and
        // report every configured skill as unknown — a mass false positive that teaches the server
        // owner to ignore this warning entirely. The delay lands well past the last plugin's enable.
        //
        // GLOBAL REGION scheduler, not BukkitScheduler: paper-plugin.yml declares folia-supported,
        // and Bukkit.getScheduler() throws UnsupportedOperationException on Folia. This work touches
        // no entity and no world, so the global region is the correct owner for it.
        //
        // No origin sender on this path: nobody typed a command, so the report goes to the console
        // and to whichever admins happen to be online (normally none, one second into boot).
        Bukkit.getGlobalRegionScheduler().runDelayed(
                this,
                task -> skillValidationReporter.validateAndReport(totemsConfig.registry(), null),
                SkillValidationReporter.STARTUP_DELAY_TICKS);

        getLogger().info("PolaroidTotems enabled with " + totemsConfig.registry().size()
                + " totem type(s).");
    }

    /**
     * Opens the configured storage backend, degrading to an in-memory one rather than failing.
     *
     * <p>Runs EXACTLY ONCE, from {@code onEnable}. The schema is created here and nowhere else; no
     * reload path reaches this method.
     *
     * <p>A failure costs cooldown PERSISTENCE, not the plugin. An unreachable MySQL or a driver that
     * did not resolve leaves the server with cooldowns that work perfectly within the session and
     * reset on restart — while totems, stack sizes, effects and skills carry on untouched. Refusing
     * to enable over an optional persistence layer would be a far larger outage than the one being
     * reported.
     */
    private CooldownRepository openRepository() {
        // data.yml is read here and nowhere else. It is deliberately NOT part of ConfigManager: that
        // class is re-read on every reload, and a connection string that looks reloadable but is not
        // is the worst kind of config key. See StorageSettings.
        saveResource("data.yml", false);
        var dataYml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.File(getDataFolder(), "data.yml"));
        StorageSettings settings = StorageSettings.parse(dataYml, getLogger()::warning);

        try {
            CooldownRepository repository = SqlCooldownRepository.open(settings, getDataFolder());
            repository.createSchema();
            getLogger().info("Cooldown storage ready (" + settings.type().name().toLowerCase()
                    + ", pool size " + settings.effectivePoolSize() + ").");
            return repository;
        } catch (StorageException failed) {
            getLogger().severe("Could not open cooldown storage (" + settings.type() + "): "
                    + failed.getMessage());
            getLogger().severe("Cooldowns will work for this session but will NOT survive a restart."
                    + " Every other feature is unaffected. Fix data.yml and restart to persist them.");
            return new MemoryCooldownRepository();
        }
    }

    @Override
    public void onDisable() {
        // The one place the pool is closed, and the last chance to persist anything still dirty.
        //
        // Synchronous by necessity: the async scheduler is being torn down alongside us, so work
        // handed to it here would never run and the rows would be lost — which is precisely the
        // failure the whole flush design exists to prevent. See CooldownService#flushAndClose.
        //
        // Null-guarded because onDisable runs even when onEnable threw partway through.
        if (cooldownService != null) {
            cooldownService.flushAndClose();
        }
    }

    /** Re-reads every YAML file. There is no runtime state to rebuild beyond these. */
    public void reloadEverything() {
        reloadEverything(null);
    }

    /**
     * Re-reads every YAML file and validates the configured MythicMobs skill names.
     *
     * <p>Validation lives HERE rather than in {@code TotemsCommand.reload} because this is the
     * shared reload path: any future caller (a plugin-message reload, a config-watcher) gets the
     * check for free instead of having to remember it. The command's sender-specific concern is
     * handled by passing that sender through as {@code origin}, which is all the reporter needs to
     * guarantee an admin who typed the command is messaged exactly once rather than twice.
     *
     * <p>Synchronous, unlike the startup path: by the time anyone can type {@code /totems reload},
     * MythicMobs has long finished loading, and deferring the answer would both make the command
     * feel broken and risk reporting to a sender who has already disconnected.
     *
     * <h2>This method does NOT touch the database. Deliberately.</h2>
     *
     * <p>This is the reload seam, so it is the one place that would be tempted to. It must not, and
     * the rules are absolute:
     *
     * <ul>
     *   <li><strong>The Hikari pool is never closed or reopened here.</strong> Dropping a live pool
     *       mid-session aborts in-flight writes and loses every cooldown that had not been flushed.
     *       A reload is a config edit, not a restart.</li>
     *   <li><strong>No schema or migration runs here.</strong> DDL belongs to {@code onEnable} and
     *       ran there once; re-running it turns a config edit into a table lock.</li>
     *   <li><strong>{@code data.yml} is not re-read here.</strong> It is read once, at enable, for
     *       exactly that reason — changing {@code type}, the sqlite file or the MySQL host needs a
     *       full restart, and the file says so.</li>
     *   <li><strong>Nothing walks {@code getDataFolder()} or {@code data/}.</strong> A reload that
     *       listed the data folder would eventually hand the {@code .db} file to a YAML parser on
     *       the main thread.</li>
     *   <li><strong>No unbounded table is loaded.</strong> Cooldowns arrive per player, on join.</li>
     * </ul>
     *
     * <p>The in-memory cache is left alone too: it holds live state for online players, and a
     * config reload has no bearing on how long they have left to wait.
     *
     * @param origin the sender who asked for the reload, or null when nobody did
     */
    public void reloadEverything(@Nullable CommandSender origin) {
        configManager.reload();
        messageService.reload(configManager.language());
        totemsConfig.reload();
        skillValidationReporter.validateAndReport(totemsConfig.registry(), origin);
    }

    /** The current snapshot of totem types. Swapped wholesale on reload, never mutated. */
    public TotemRegistry totemRegistry() {
        return totemsConfig.registry();
    }

    public TotemService totemService() {
        return totemService;
    }
}
