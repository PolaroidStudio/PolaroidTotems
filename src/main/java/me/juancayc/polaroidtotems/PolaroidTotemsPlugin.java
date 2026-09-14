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
import me.juancayc.polaroidtotems.totem.TotemRegistry;
import me.juancayc.polaroidtotems.totem.TotemService;
import me.juancayc.polaroidtotems.util.SoundService;
import org.bukkit.plugin.java.JavaPlugin;

/** Plugin entry point. Wires the layers together and owns the enable/disable lifecycle. */
public final class PolaroidTotemsPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private TotemsConfig totemsConfig;
    private MessageService messageService;
    private TotemService totemService;

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

        this.totemService = new TotemService(this, configManager, totemsConfig, stamper,
                messageService, soundService);

        getServer().getPluginManager().registerEvents(
                new ResurrectListener(configManager, totemService), this);
        getServer().getPluginManager().registerEvents(
                new NormalizationListener(configManager, totemsConfig, stamper), this);

        new CommandRegistrar(this, messageService, stamper).register();

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
        configManager.reload();
        messageService.reload(configManager.language());
        totemsConfig.reload();
    }

    /** The current snapshot of totem types. Swapped wholesale on reload, never mutated. */
    public TotemRegistry totemRegistry() {
        return totemsConfig.registry();
    }

    public TotemService totemService() {
        return totemService;
    }
}
