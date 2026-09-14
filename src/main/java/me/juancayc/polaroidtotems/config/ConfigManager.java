package me.juancayc.polaroidtotems.config;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

/**
 * Reloadable feature configuration ({@code config.yml}).
 *
 * <p>Totem types are not here — see {@link TotemsConfig}. {@link #reload()} re-reads this file only.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
    }

    /** Re-reads config.yml from disk. */
    public void reload() {
        plugin.reloadConfig();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public String language() {
        return cfg().getString("language", "en");
    }

    // ----- activation -------------------------------------------------------------------------

    /**
     * Master switch for inventory-wide activation.
     *
     * <p>False leaves vanilla behaviour untouched: only a totem held in a hand saves the player,
     * and the plugin limits itself to stack sizes and the effects of hand-held custom totems.
     */
    public boolean activateFromInventory() {
        return cfg().getBoolean("activation.from-inventory", true);
    }

    /** Also consider the off-hand and armour slots, not just the 36 main-inventory slots. */
    public boolean activateFromArmor() {
        return cfg().getBoolean("activation.include-armor-slots", false);
    }

    /**
     * Permission a player needs for inventory-wide activation, or null when it is open to everyone.
     *
     * <p>A player without it still gets the ordinary hand-held vanilla behaviour, because that
     * branch belongs to the server, not to this plugin.
     */
    public @org.jetbrains.annotations.Nullable String activationPermission() {
        String raw = cfg().getString("activation.require-permission", "");
        return raw == null || raw.isBlank() ? null : raw;
    }

    // ----- normalization ----------------------------------------------------------------------

    /**
     * Whether untagged totems found in an inventory are stamped as the reserved {@code vanilla}
     * type.
     *
     * <p>This is what makes per-type stack sizes actually hold: stacking is decided by component
     * identity, so a plain totem and a stamped one never merge. Normalizing on pickup, join and
     * inventory interaction keeps a player's totems in one stack instead of a shelf of ones.
     */
    public boolean normalizeEnabled() {
        return cfg().getBoolean("normalization.enabled", true);
    }

    public boolean normalizeOnPickup() {
        return cfg().getBoolean("normalization.on-pickup", true);
    }

    public boolean normalizeOnJoin() {
        return cfg().getBoolean("normalization.on-join", true);
    }

    public boolean normalizeOnInventoryClick() {
        return cfg().getBoolean("normalization.on-inventory-click", true);
    }

    public boolean normalizeOnInventoryOpen() {
        return cfg().getBoolean("normalization.on-inventory-open", true);
    }

    // ----- resurrection behaviour ---------------------------------------------------------------

    /**
     * Ticks to wait before applying a totem's custom effects.
     *
     * <p>Clamped to at least one tick, and that floor is the whole point. Vanilla applies its own
     * Regeneration II / Absorption II / Fire Resistance as part of the resurrect branch, AFTER the
     * plugin un-cancels the event. Anything applied in the same tick is overwritten by that.
     */
    public long effectDelayTicks() {
        return Math.max(1L, cfg().getLong("resurrection.effect-delay-ticks", 1L));
    }

    /** Whether the vanilla totem animation is played for a resurrection the plugin forced. */
    public boolean playAnimation() {
        return cfg().getBoolean("resurrection.play-animation", true);
    }

    /** Whether a player is told in chat which totem saved them. */
    public boolean announceToPlayer() {
        return cfg().getBoolean("resurrection.announce", true);
    }

    // ----- sound --------------------------------------------------------------------------------

    /** The one plugin sound, played on a successful resurrection. */
    public Sound resurrectSound() {
        return sound(cfg().getString("sound.resurrect", "ITEM_TOTEM_USE"), "item.totem.use");
    }

    public float resurrectVolume() {
        return (float) cfg().getDouble("sound.volume", 1.0);
    }

    public float resurrectPitch() {
        return (float) cfg().getDouble("sound.pitch", 1.0);
    }

    private Sound sound(String name, String fallbackKey) {
        Sound resolved = Registry.SOUNDS.get(
                NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT).replace('_', '.')));
        if (resolved == null) {
            plugin.getLogger().warning("Unknown sound '" + name + "', falling back to " + fallbackKey + ".");
            resolved = Registry.SOUNDS.get(NamespacedKey.minecraft(fallbackKey));
        }
        return resolved;
    }
}
