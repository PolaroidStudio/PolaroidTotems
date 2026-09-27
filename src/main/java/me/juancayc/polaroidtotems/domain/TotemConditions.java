package me.juancayc.polaroidtotems.domain;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * When a totem type is allowed to fire, as its {@code conditions:} section describes it.
 *
 * <p>Two independent filters, both optional:
 *
 * <ul>
 *   <li>{@code causes} — the {@link DamageCause} of the lethal hit ({@code VOID}, {@code FALL},
 *       {@code LAVA}, …).</li>
 *   <li>{@code environments} — the {@link World.Environment} of the world the player is dying in
 *       ({@code NORMAL}, {@code NETHER}, {@code THE_END}, {@code CUSTOM}).</li>
 * </ul>
 *
 * <p>An EMPTY set means "any": a totem with no {@code conditions:} section at all therefore matches
 * everything, which is exactly how every totem behaved before this key existed. Both filters must
 * pass for the totem to be usable.
 *
 * <h2>What a non-match means</h2>
 *
 * <p>A totem whose conditions do not match is treated as NOT USABLE for this death, precisely like
 * one on cooldown or one whose permission the player lacks: the inventory search simply skips it and
 * keeps looking. It is deliberately NOT reported as a cooldown refusal — the player is not waiting
 * for anything, this totem just was never meant for this kind of death.
 *
 * <h2>Why enums and not strings</h2>
 *
 * <p>Both types are plain Java enums, so parsing them here keeps the death path free of string
 * handling and the whole class testable without a server: {@code DamageCause.valueOf} touches no
 * registry. Unknown names are dropped with a warning at parse time, the same tolerant spirit as
 * {@link TotemDefinition#clampStackSize} — one typo costs that one entry, never the totem type.
 *
 * @param causes       the lethal damage causes this type fires for; empty means any cause
 * @param environments the world environments this type fires in; empty means any environment
 */
public record TotemConditions(Set<DamageCause> causes, Set<World.Environment> environments) {

    /** No restriction at all. What an absent {@code conditions:} section produces. */
    public static final TotemConditions ANY = new TotemConditions(Set.of(), Set.of());

    public TotemConditions {
        // EnumSet copies rather than Set.copyOf: iteration order stays the enum's declaration
        // order, which keeps warnings and diagnostics stable, and membership is a bit test.
        causes = causes == null || causes.isEmpty()
                ? Set.of()
                : java.util.Collections.unmodifiableSet(EnumSet.copyOf(causes));
        environments = environments == null || environments.isEmpty()
                ? Set.of()
                : java.util.Collections.unmodifiableSet(EnumSet.copyOf(environments));
    }

    /** Builds conditions from plain collections. Exists mainly so tests read naturally. */
    public static TotemConditions of(Collection<DamageCause> causes,
                                     Collection<World.Environment> environments) {
        return new TotemConditions(
                causes == null || causes.isEmpty() ? Set.of() : EnumSet.copyOf(causes),
                environments == null || environments.isEmpty() ? Set.of() : EnumSet.copyOf(environments));
    }

    /** True when neither filter is set, so this type fires for any death, anywhere. */
    public boolean isUnrestricted() {
        return causes.isEmpty() && environments.isEmpty();
    }

    /**
     * Whether a death with this cause, in a world of this environment, may be prevented by this type.
     *
     * <p>The one predicate the whole feature reduces to. Takes the two values rather than an event
     * and a {@code World} so it can be tested without a server.
     *
     * <p>A null argument means "not known". It only fails a filter that is actually SET: a totem
     * restricted to {@code VOID} must never fire for a death whose cause could not be determined,
     * but an unrestricted totem has no reason to care.
     *
     * @param cause       the cause of the lethal hit, or null when it could not be determined
     * @param environment the environment of the world the player is in, or null when unknown
     */
    public boolean matches(@Nullable DamageCause cause, @Nullable World.Environment environment) {
        if (!causes.isEmpty() && (cause == null || !causes.contains(cause))) return false;
        return environments.isEmpty() || (environment != null && environments.contains(environment));
    }

    /**
     * Whether this cause is LISTED explicitly, as opposed to merely allowed by an empty filter.
     *
     * <p>Used for deaths vanilla would never let any totem prevent — see {@link
     * me.juancayc.polaroidtotems.listeners.VoidRescueListener}. For those, "no conditions means any
     * cause" would silently turn every ordinary totem on the server into a void rescue, which is a
     * change no server owner asked for. Only a type that names the cause opts in.
     */
    public boolean listsCause(@Nullable DamageCause cause) {
        return cause != null && causes.contains(cause);
    }

    /**
     * Reads one totem's {@code conditions:} section.
     *
     * <p>Defensive like every other parser in this plugin: a malformed value costs a warning and
     * that value, never an exception, and never the totem type.
     *
     * @param section   the {@code conditions:} section, or null when the totem omits it
     * @param totemId   named in every warning so a server owner can find the offending entry
     * @param onWarning receives one human-readable line per rejected value
     */
    public static TotemConditions parse(@Nullable ConfigurationSection section, String totemId,
                                        Consumer<String> onWarning) {
        if (section == null) return ANY;

        Set<DamageCause> causes = parseEnumList(section, "causes", DamageCause.class, totemId, onWarning);
        Set<World.Environment> environments =
                parseEnumList(section, "environments", World.Environment.class, totemId, onWarning);
        return of(causes, environments);
    }

    /**
     * Parses one list of enum constant names, case-insensitively.
     *
     * <p>Note the failure mode this guards against: a list whose EVERY entry is a typo parses to an
     * empty set, and an empty set means "any". So {@code causes: [VIOD]} does not silently become a
     * totem that fires for every cause — it warns, loudly, naming the value, and the owner sees that
     * the filter they wrote is not the filter they got.
     */
    private static <E extends Enum<E>> Set<E> parseEnumList(ConfigurationSection section, String key,
                                                           Class<E> type, String totemId,
                                                           Consumer<String> onWarning) {
        if (!section.contains(key)) return Set.of();

        List<String> raw;
        if (section.isList(key)) {
            raw = section.getStringList(key);
        } else {
            // `causes: VOID` instead of a list. Accepting the single value is kinder than dropping
            // the whole filter, and dropping it would silently widen the totem to every cause.
            String single = section.getString(key);
            raw = single == null ? List.of() : List.of(single);
        }

        Set<E> parsed = EnumSet.noneOf(type);
        for (String value : raw) {
            if (value == null || value.isBlank()) continue;
            String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            try {
                parsed.add(Enum.valueOf(type, normalized));
            } catch (IllegalArgumentException unknown) {
                onWarning.accept("Totem '" + totemId + "' lists unknown " + key + " value '" + value
                        + "' under 'conditions'; ignoring that entry.");
            }
        }

        if (parsed.isEmpty() && !raw.isEmpty()) {
            onWarning.accept("Totem '" + totemId + "': no valid 'conditions." + key
                    + "' value remained, so that filter is OFF and the totem is not restricted by it.");
        }
        return parsed;
    }
}
