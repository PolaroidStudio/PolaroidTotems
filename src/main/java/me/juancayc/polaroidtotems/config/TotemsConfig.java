package me.juancayc.polaroidtotems.config;

import me.juancayc.polaroidtotems.domain.MythicSkillSpec;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.domain.TotemEffectSpec;
import me.juancayc.polaroidtotems.totem.TotemRegistry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Reads {@code totems.yml} into a {@link TotemRegistry}. Fully re-readable on reload. */
public final class TotemsConfig {

    private final JavaPlugin plugin;
    private volatile TotemRegistry registry = TotemRegistry.empty();

    public TotemsConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "totems.yml");
        if (!file.exists()) {
            plugin.saveResource("totems.yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        this.registry = parse(cfg, message -> plugin.getLogger().warning(message));
    }

    public TotemRegistry registry() {
        return registry;
    }

    /**
     * Parses a loaded configuration into a registry. Static and Bukkit-free apart from the config
     * type, so tests can feed it a {@link YamlConfiguration} built from a string.
     *
     * <p>Every rejection is a warning, never an exception: a broken entry costs that one totem type,
     * not the whole plugin.
     */
    public static TotemRegistry parse(YamlConfiguration cfg, Consumer<String> onWarning) {
        ConfigurationSection totems = cfg.getConfigurationSection("totems");
        if (totems == null) {
            onWarning.accept("totems.yml has no 'totems' section; only the default vanilla totem will exist.");
            return TotemRegistry.empty();
        }

        Map<String, TotemDefinition> parsed = new LinkedHashMap<>();
        for (String rawId : totems.getKeys(false)) {
            String id = rawId.trim().toLowerCase(Locale.ROOT);
            if (id.isEmpty()) {
                onWarning.accept("Skipping a totem with an empty id in totems.yml.");
                continue;
            }
            if (parsed.containsKey(id)) {
                onWarning.accept("Duplicate totem id '" + id + "' in totems.yml; keeping the first one.");
                continue;
            }

            ConfigurationSection section = totems.getConfigurationSection(rawId);
            if (section == null) {
                onWarning.accept("Totem '" + id + "' is not a section; skipping it.");
                continue;
            }
            parsed.put(id, parseOne(id, section, onWarning));
        }

        return TotemRegistry.of(parsed);
    }

    private static TotemDefinition parseOne(String id, ConfigurationSection section,
                                            Consumer<String> onWarning) {
        // `item: vanilla` is the shorthand every totem gets by default. The registry never resolves
        // it here — resolution is lazy, at give time, because Nexo loads its items asynchronously.
        String item = section.getString("item", "vanilla");
        if (item == null || item.isBlank() || item.equalsIgnoreCase("vanilla")) {
            item = "vanilla:TOTEM_OF_UNDYING";
        }

        int stackSize = TotemDefinition.clampStackSize(section.getInt("stack-size", 1), id, onWarning);

        String displayName = section.getString("display-name");
        if (displayName != null && displayName.isBlank()) displayName = null;

        List<String> lore = section.getStringList("lore");

        // Two independent ways to give a totem its own look, both optional. `item-model` is the
        // modern one (a namespaced key naming a model); `custom-model-data` is the legacy number,
        // kept because existing resource packs still select on it. Neither is parsed into a Bukkit
        // type here — that keeps this whole class server-free and unit-testable.
        String itemModel = section.getString("item-model");
        if (itemModel != null && itemModel.isBlank()) itemModel = null;

        Integer customModelData = section.contains("custom-model-data")
                ? section.getInt("custom-model-data")
                : null;

        // The reserved vanilla entry deliberately carries no custom effects by default: it describes
        // the plain totem, and a plain totem is what vanilla already does. A server owner may still
        // add some explicitly.
        List<TotemEffectSpec> effects = TotemEffectSpec.parseList(section, "effects", onWarning);

        // Parsed unconditionally, even on a server with no MythicMobs: the names are just strings
        // here and the hook decides at cast time whether there is anything to call. Rejecting the
        // key when Mythic is absent would make a config silently lose entries on a server that is
        // about to install it.
        List<MythicSkillSpec> skills = MythicSkillSpec.parseList(section, "skills", onWarning);

        boolean consume = section.getBoolean("consume", true);
        boolean healToFull = section.getBoolean("heal-to-full", false);

        String permission = section.getString("permission");
        if (permission != null && permission.isBlank()) permission = null;

        return new TotemDefinition(id, displayName, lore, item, stackSize, itemModel,
                customModelData, effects, skills, consume, healToFull, permission);
    }
}
