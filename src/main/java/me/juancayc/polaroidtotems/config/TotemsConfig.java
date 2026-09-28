package me.juancayc.polaroidtotems.config;

import me.juancayc.polaroidtotems.domain.MythicSkillSpec;
import me.juancayc.polaroidtotems.domain.TotemConditions;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.domain.TotemEffectSpec;
import me.juancayc.polaroidtotems.domain.TotemMode;
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

        // Read as a long, not an int: a cooldown of a week is 604800 seconds, which fits an int, but
        // the value is multiplied into epoch millis downstream and a long here keeps the whole chain
        // one type. An absent key is NO_COOLDOWN, which is also what an explicit 0 means.
        long cooldownSeconds = TotemDefinition.clampCooldownSeconds(
                section.getLong("cooldown", TotemDefinition.NO_COOLDOWN), id, onWarning);

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

        // Both optional and both default to "exactly how a totem behaved before these keys existed":
        // no conditions fires for any death anywhere, and no rescue teleports nobody.
        if (section.contains("conditions") && !section.isConfigurationSection("conditions")) {
            onWarning.accept("Totem '" + id + "': 'conditions' must be a section with 'causes:' and/or "
                    + "'environments:' lists; ignoring it, so the totem is not restricted.");
        }
        TotemConditions conditions = TotemConditions.parse(
                section.getConfigurationSection("conditions"), id, onWarning);

        boolean returnToSafeGround = section.getBoolean("rescue.return-to-safe-ground", false);

        TotemMode mode = TotemMode.parse(section.getString("mode"), id, onWarning);
        boolean keepExperience = section.getBoolean("keep-experience", false);

        if (mode == TotemMode.KEEP_INVENTORY) {
            // A keep-inventory type never resurrects anybody, so every key that only describes a
            // resurrection is meaningless on it. Each one is dropped with its own warning rather than
            // failing the type: the owner almost certainly copied a resurrect entry and changed its
            // mode, and the rest of that entry (item, stack size, cooldown, conditions, look) is still
            // exactly what they want. Emptying the values HERE means no death path ever has to ask
            // "but which mode is this?" before reading them.
            //
            // Only values that would actually DO something are reported: `heal-to-full: false` or an
            // empty `effects:` list is already a no-op and not worth a line in the console.
            if (!consume) {
                onWarning.accept("Totem '" + id + "' is a keep-inventory totem but sets 'consume: false'; "
                        + "a keep-inventory totem always consumes one unit per death, so it is ignored.");
                consume = true;
            }
            if (healToFull) {
                onWarning.accept(ignoredOnKeeper(id, "heal-to-full"));
                healToFull = false;
            }
            if (!effects.isEmpty()) {
                onWarning.accept(ignoredOnKeeper(id, "effects"));
                effects = List.of();
            }
            if (!skills.isEmpty()) {
                onWarning.accept(ignoredOnKeeper(id, "skills"));
                skills = List.of();
            }
            if (returnToSafeGround) {
                onWarning.accept(ignoredOnKeeper(id, "rescue.return-to-safe-ground"));
                returnToSafeGround = false;
            }
        } else if (keepExperience) {
            // The mirror image: a resurrected player never dies, so there is no experience to keep.
            onWarning.accept("Totem '" + id + "' sets 'keep-experience' but is not a keep-inventory "
                    + "totem (mode: keep-inventory); a resurrection never loses experience, so it is ignored.");
            keepExperience = false;
        }

        // Not an error, but almost certainly a mistake worth one line: a VOID totem that does not
        // move the player saves them at 1 HP while they are still falling through the void, so the
        // very next void hit kills them anyway. The totem is spent for half a second of life.
        //
        // A keep-inventory type is exempt: on a void death it lets the player die ANYWAY and keeps
        // their items, which is a perfectly sensible thing to restrict to the void.
        if (mode == TotemMode.RESURRECT
                && conditions.listsCause(org.bukkit.event.entity.EntityDamageEvent.DamageCause.VOID)
                && !returnToSafeGround) {
            onWarning.accept("Totem '" + id + "' fires on VOID damage but has no "
                    + "'rescue.return-to-safe-ground: true'; the player will be saved while still "
                    + "in the void and die again moments later.");
        }

        return new TotemDefinition(id, displayName, lore, item, stackSize, cooldownSeconds, itemModel,
                customModelData, effects, skills, consume, healToFull, permission, conditions,
                returnToSafeGround, mode, keepExperience);
    }

    /** The one warning shape for a resurrection-only key found on a keep-inventory type. */
    private static String ignoredOnKeeper(String id, String key) {
        return "Totem '" + id + "' is a keep-inventory totem; '" + key + "' only applies to a "
                + "resurrection, so it is ignored.";
    }
}
