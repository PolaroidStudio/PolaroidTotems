package me.juancayc.polaroidtotems.domain;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * What a totem type does for the player carrying it, as its {@code mode:} key describes it.
 *
 * <p>Two answers, and they never overlap. A type is exactly one of them, and every path in the
 * plugin asks for exactly one:
 *
 * <ul>
 *   <li>{@link #RESURRECT} — the classic totem, and the default. It cancels the death: the player
 *       stays alive, on 1 HP (or full health), with the type's effects, skills and rescue. Every
 *       totem that existed before this key did exactly this, so an absent {@code mode:} keeps it.</li>
 *   <li>{@link #KEEP_INVENTORY} — the player DOES die, but respawns with everything they carried.
 *       Found only by the death listener; the resurrection paths skip it outright, so a keeper can
 *       never be spent on a resurrection by accident.</li>
 * </ul>
 *
 * <p>Plain Java, no Bukkit type in sight, so parsing is testable without a server.
 */
public enum TotemMode {

    /** Cancel the death. The default, and the only behaviour before {@code mode:} existed. */
    RESURRECT("resurrect"),

    /** Let the player die, but keep their inventory. Exactly one unit is consumed per death. */
    KEEP_INVENTORY("keep-inventory");

    private final String configName;

    TotemMode(String configName) {
        this.configName = configName;
    }

    /** The spelling {@code totems.yml} uses for this mode. */
    public String configName() {
        return configName;
    }

    /**
     * Reads one {@code mode:} value.
     *
     * <p>Tolerant in both directions a server owner is likely to stray: case is ignored, and
     * underscores or spaces are read as dashes, so {@code KEEP_INVENTORY} and {@code keep inventory}
     * both mean {@code keep-inventory}. A value that still names nothing falls back to {@link
     * #RESURRECT} with a warning — the safe direction, because it is what the type did before the
     * owner touched the key, and it never silently lets a player die who expected to be saved.
     *
     * @param raw       the configured value, or null when the key is absent
     * @param totemId   named in the warning so a server owner can find the offending entry
     * @param onWarning receives one line when the value is not recognised
     */
    public static TotemMode parse(@Nullable String raw, String totemId, Consumer<String> onWarning) {
        if (raw == null || raw.isBlank()) return RESURRECT;

        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        for (TotemMode mode : values()) {
            if (mode.configName.equals(normalized)) return mode;
        }
        onWarning.accept("Totem '" + totemId + "' has unknown mode '" + raw + "'; expected 'resurrect' or "
                + "'keep-inventory'. Using 'resurrect'.");
        return RESURRECT;
    }
}
