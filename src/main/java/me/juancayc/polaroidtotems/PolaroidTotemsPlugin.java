package me.juancayc.polaroidtotems;

import me.juancayc.polaroidtotems.commands.CommandRegistrar;
import me.juancayc.polaroidtotems.config.ConfigManager;
import me.juancayc.polaroidtotems.config.TotemsConfig;
import me.juancayc.polaroidtotems.item.ItemManager;
import me.juancayc.polaroidtotems.item.TotemStamper;
import me.juancayc.polaroidtotems.item.hooks.NexoItemHook;
import me.juancayc.polaroidtotems.item.hooks.VanillaItemHook;
import me.juancayc.polaroidtotems.listeners.NormalizationListener;
import me.juancayc.polaroidtotems.listeners.ResurrectListener;
import me.juancayc.polaroidtotems.messaging.ColorFormats;
import me.juancayc.polaroidtotems.messaging.MessageService;
import me.juancayc.polaroidtotems.messaging.MiniMessageProvider;
import me.juancayc.polaroidtotems.skill.MythicSkillHook;
import me.juancayc.polaroidtotems.skill.SkillValidationReporter;
import me.juancayc.polaroidtotems.skill.SkillValidator;
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

        this.totemService = new TotemService(this, configManager, totemsConfig, stamper,
                messageService, soundService, skillHook);

        getServer().getPluginManager().registerEvents(
                new ResurrectListener(configManager, totemService), this);
        getServer().getPluginManager().registerEvents(
                new NormalizationListener(configManager, totemsConfig, stamper), this);

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

    @Override
    public void onDisable() {
        // Nothing to close: the plugin holds no pool, no timer and no per-player state. A totem's
        // identity lives in the item itself, so an unclean shutdown loses nothing.
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
