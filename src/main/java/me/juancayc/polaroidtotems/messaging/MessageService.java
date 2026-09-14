package me.juancayc.polaroidtotems.messaging;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Loads {@code lang/messages_<language>.yml} (with English fallback via {@code setDefaults}) and
 * builds/sends localized components.
 *
 * <p>Every key is either a plain MiniMessage string or a {@code {text, hover, click}} section. The
 * Polaroid prefix lives in its own top-level {@code prefix:} key and is composed onto a message IN
 * CODE by {@link #sendPrefixed} — no message value may contain a literal {@code <prefix>} tag,
 * because nothing would resolve it there (POLAROID-STYLE.md section 3). List rows and hover text go
 * through {@link #sendRaw} / {@link #build} and never carry the prefix.
 */
public final class MessageService {

    private final JavaPlugin plugin;
    private final MiniMessage mm;
    private volatile YamlConfiguration messages;

    public MessageService(JavaPlugin plugin, MiniMessage mm) {
        this.plugin = plugin;
        this.mm = mm;
    }

    /** Loads (or reloads) the active language file. Cheap; safe to call from the reload command. */
    public void reload(String language) {
        saveResourceIfMissing("lang/messages_en.yml");
        File file = new File(plugin.getDataFolder(), "lang/messages_" + language + ".yml");
        if (!file.exists()) saveResourceIfMissing("lang/messages_" + language + ".yml");
        if (!file.exists()) file = new File(plugin.getDataFolder(), "lang/messages_en.yml");

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("lang/messages_en.yml")) {
            if (in != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
            // Falls back to whatever the file on disk already has.
        }
        this.messages = cfg;
    }

    private void saveResourceIfMissing(String path) {
        if (plugin.getResource(path) != null && !new File(plugin.getDataFolder(), path).exists()) {
            plugin.saveResource(path, false);
        }
    }

    /** The Polaroid prefix, built fresh from the current lang file each call. */
    public Component prefix() {
        return mm.deserialize(messages.getString("prefix", ""));
    }

    /** Builds a component from a message key, applying %token%/value replacement pairs. */
    public Component build(String key, String... replacements) {
        ConfigurationSection sec = messages.getConfigurationSection(key);

        String text;
        String hover = null;
        String click = null;

        if (sec != null) {
            text = sec.getString("text", "");
            hover = sec.getString("hover", null);
            click = sec.getString("click", null);
        } else {
            text = messages.getString(key, "<red>Missing message key: " + key);
        }

        text = applyReplacements(text, replacements);
        hover = applyReplacements(hover, replacements);
        click = applyReplacements(click, replacements);

        Component component = mm.deserialize(text);
        if (hover != null && !hover.isBlank()) {
            component = component.hoverEvent(HoverEvent.showText(mm.deserialize(hover)));
        }
        if (click != null && !click.isBlank()) {
            component = ClickActionParser.apply(component, click, "");
        }
        return component;
    }

    /** Renders an arbitrary config string (a totem display name, a lore line) through MiniMessage. */
    public Component render(String raw, String... replacements) {
        if (raw == null || raw.isBlank()) return Component.empty();
        return mm.deserialize(applyReplacements(raw, replacements));
    }

    /** Standalone result message: prefixed. Players get hover/click, console gets plain text. */
    public void sendPrefixed(CommandSender sender, String key, String... replacements) {
        deliver(sender, prefix().append(build(key, replacements)));
    }

    /** List row / already-headed message: no prefix composed in. */
    public void sendRaw(CommandSender sender, String key, String... replacements) {
        deliver(sender, build(key, replacements));
    }

    private void deliver(CommandSender sender, Component component) {
        if (sender instanceof Player player) {
            player.sendMessage(component);
        } else {
            sender.sendMessage(PlainTextComponentSerializer.plainText().serialize(component));
        }
    }

    /**
     * Escapes a value so it cannot inject formatting. A player called {@code <red>} must not be able
     * to colour someone else's chat (POLAROID-STYLE.md rule 7).
     */
    public String escape(String value) {
        return value == null ? "" : mm.escapeTags(value);
    }

    private static String applyReplacements(String text, String[] replacements) {
        if (text == null || replacements == null || replacements.length == 0) return text;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return text;
    }
}
