package me.juancayc.polaroidtotems.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.juancayc.polaroidtotems.PolaroidTotemsPlugin;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.item.TotemStamper;
import me.juancayc.polaroidtotems.messaging.MessageService;
import me.juancayc.polaroidtotems.totem.TotemRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * {@code /totems} — the whole command surface.
 *
 * <pre>
 *   /totems help
 *   /totems list
 *   /totems give &lt;player&gt; &lt;type&gt; [amount]
 *   /totems reload
 * </pre>
 *
 * <p>This is a {@link BasicCommand}, not a {@code CommandExecutor}. Paper plugins cannot declare
 * commands in {@code paper-plugin.yml}, and {@code JavaPlugin#getCommand} throws for them during
 * startup; {@code JavaPlugin#registerCommand} accepts only this interface. {@code BasicCommand}
 * keeps the familiar {@code String[] args} shape, so the routing below is unchanged.
 *
 * <p>{@link #permission()} is deliberately NOT overridden. Paper would apply it to the whole
 * command, hiding {@code help} from everyone who lacks it. Each branch checks its own permission.
 */
public final class TotemsCommand implements BasicCommand {

    public static final String PERMISSION_ADMIN = "polaroidtotems.admin";

    private static final int MAX_GIVE_AMOUNT = 64 * 36;

    private final PolaroidTotemsPlugin plugin;
    private final MessageService messages;
    private final TotemStamper stamper;

    public TotemsCommand(PolaroidTotemsPlugin plugin, MessageService messages, TotemStamper stamper) {
        this.plugin = plugin;
        this.messages = messages;
        this.stamper = stamper;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> list(sender);
            case "give" -> give(sender, args);
            case "reload" -> reload(sender);
            default -> sendHelp(sender);
        }
    }

    // ----- branches ------------------------------------------------------------------------------

    private void list(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            messages.sendPrefixed(sender, "general.no_permission");
            return;
        }

        TotemRegistry registry = plugin.totemRegistry();
        messages.sendRaw(sender, "list.header", "%count%", String.valueOf(registry.size()));
        for (TotemDefinition definition : registry.all()) {
            messages.sendRaw(sender, "list.entry",
                    "%totem%", messages.escape(definition.id()),
                    "%stack_size%", String.valueOf(definition.stackSize()),
                    "%effects%", String.valueOf(definition.effects().size()),
                    "%item%", messages.escape(definition.item()));
        }
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            messages.sendPrefixed(sender, "general.no_permission");
            return;
        }
        if (args.length < 3) {
            messages.sendRaw(sender, "help.give");
            return;
        }

        String targetName = args[1];
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            messages.sendPrefixed(sender, "general.player_not_found",
                    "%player%", messages.escape(targetName));
            return;
        }

        String typeId = args[2].toLowerCase(Locale.ROOT);
        TotemDefinition definition = plugin.totemRegistry().get(typeId);
        if (definition == null) {
            messages.sendPrefixed(sender, "totem.unknown_type",
                    "%totem%", messages.escape(args[2]));
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException notANumber) {
                messages.sendPrefixed(sender, "general.invalid_number",
                        "%value%", messages.escape(args[3]));
                return;
            }
        }
        amount = Math.clamp(amount, 1, MAX_GIVE_AMOUNT);

        // Resolved here, at use time — never at startup. Nexo loads its items asynchronously, so an
        // id that is null during onEnable resolves perfectly once Nexo has finished.
        ItemStack stack = stamper.create(definition, amount);
        if (stack == null) {
            messages.sendPrefixed(sender, "totem.unresolvable_item",
                    "%totem%", messages.escape(definition.id()),
                    "%item%", messages.escape(definition.item()));
            return;
        }

        // addItem returns whatever did not fit, so a full inventory is reported rather than silently
        // eaten.
        var leftover = target.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            messages.sendPrefixed(sender, "totem.inventory_full",
                    "%player%", messages.escape(target.getName()));
            return;
        }

        messages.sendPrefixed(sender, "totem.given",
                "%amount%", String.valueOf(amount),
                "%totem%", messages.escape(definition.id()),
                "%player%", messages.escape(target.getName()));

        if (!sender.equals(target)) {
            messages.sendPrefixed(target, "totem.received",
                    "%amount%", String.valueOf(amount),
                    "%totem%", messages.escape(definition.id()));
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission(PERMISSION_ADMIN)) {
            messages.sendPrefixed(sender, "general.no_permission");
            return;
        }
        // The sender is handed through so the skill-name validation that runs inside can report to
        // them without ALSO reaching them a second time as an online admin. Ordered so "reloaded"
        // lands first and any findings read as its consequence.
        messages.sendPrefixed(sender, "general.reloaded");
        plugin.reloadEverything(sender);
    }

    private void sendHelp(CommandSender sender) {
        messages.sendRaw(sender, "help.header");
        if (sender.hasPermission(PERMISSION_ADMIN)) {
            messages.sendRaw(sender, "help.list");
            messages.sendRaw(sender, "help.give");
            messages.sendRaw(sender, "help.reload");
        } else {
            messages.sendRaw(sender, "help.no_admin");
        }
    }

    // ----- suggestions ---------------------------------------------------------------------------

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();

        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> options = sender.hasPermission(PERMISSION_ADMIN)
                    ? List.of("help", "list", "give", "reload")
                    : List.of("help");
            return options.stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (!sender.hasPermission(PERMISSION_ADMIN)) return List.of();

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(online.getName());
                }
            }
            return names;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return plugin.totemRegistry().ids().stream()
                    .filter(id -> id.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
