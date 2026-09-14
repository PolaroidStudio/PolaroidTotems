package me.juancayc.polaroidtotems.commands;

import me.juancayc.polaroidtotems.PolaroidTotemsPlugin;
import me.juancayc.polaroidtotems.item.TotemStamper;
import me.juancayc.polaroidtotems.messaging.MessageService;

import java.util.List;

/**
 * Registers the plugin's commands through Paper's command API.
 *
 * <p>Paper plugins cannot declare commands in {@code paper-plugin.yml} — that is a legacy
 * {@code plugin.yml} feature, and calling {@code JavaPlugin#getCommand} from a Paper plugin during
 * startup throws {@code UnsupportedOperationException}. {@code JavaPlugin#registerCommand} is the
 * supported route, and its javadoc requires it to be called from {@code onEnable()}, which is where
 * {@link #register()} runs.
 */
public final class CommandRegistrar {

    private static final String LABEL = "totems";
    private static final String DESCRIPTION = "Give, list and reload the server's custom totems.";
    private static final List<String> ALIASES = List.of("totem", "pt");

    private final PolaroidTotemsPlugin plugin;
    private final MessageService messages;
    private final TotemStamper stamper;

    public CommandRegistrar(PolaroidTotemsPlugin plugin, MessageService messages, TotemStamper stamper) {
        this.plugin = plugin;
        this.messages = messages;
        this.stamper = stamper;
    }

    /** Must run inside {@code onEnable()}; Paper rejects registration at any other time. */
    public void register() {
        TotemsCommand root = new TotemsCommand(plugin, messages, stamper);
        plugin.registerCommand(LABEL, DESCRIPTION, ALIASES, root);
    }
}
