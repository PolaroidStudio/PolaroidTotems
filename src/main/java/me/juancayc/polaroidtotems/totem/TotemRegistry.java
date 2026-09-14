package me.juancayc.polaroidtotems.totem;

import me.juancayc.polaroidtotems.domain.TotemDefinition;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable set of totem types parsed from {@code totems.yml}.
 *
 * <p>Swapped wholesale on reload, never mutated in place, so a resurrection happening during a
 * reload reads one consistent snapshot rather than a half-rebuilt map.
 *
 * <p>The reserved {@value TotemDefinition#VANILLA_ID} entry is always present: {@link #vanilla()}
 * synthesizes a default one if the file omits it, because an untagged totem must always resolve to
 * something.
 */
public final class TotemRegistry {

    private final Map<String, TotemDefinition> byId;

    private TotemRegistry(Map<String, TotemDefinition> byId) {
        // Collections.unmodifiableMap over a LinkedHashMap, NOT Map.copyOf. Map.copyOf returns an
        // immutable HASH map, which discards insertion order entirely — so `all()` and `ids()` would
        // hand back totems in an arbitrary, JVM-dependent sequence despite what they promise, and
        // `/totems list` would shuffle its rows between restarts. The defensive copy is taken here
        // so the caller's map cannot be mutated into this one afterwards.
        this.byId = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byId));
    }

    public static TotemRegistry of(Map<String, TotemDefinition> definitions) {
        Map<String, TotemDefinition> copy = new LinkedHashMap<>(definitions);
        copy.putIfAbsent(TotemDefinition.VANILLA_ID, defaultVanilla());
        return new TotemRegistry(copy);
    }

    /** An empty registry that still answers {@link #vanilla()}. */
    public static TotemRegistry empty() {
        return of(Map.of());
    }

    /** The definition for an id, or null when the file has no such type. */
    public @Nullable TotemDefinition get(String id) {
        return id == null ? null : byId.get(id);
    }

    /**
     * The definition for an id, falling back to the reserved vanilla entry.
     *
     * <p>Used on the resurrection path: a totem stamped with a type that a later config removed
     * must still behave like a totem rather than like nothing.
     */
    public TotemDefinition resolveOrVanilla(@Nullable String id) {
        TotemDefinition found = get(id);
        return found != null ? found : vanilla();
    }

    /** The reserved entry describing the plain Totem of Undying. Never null. */
    public TotemDefinition vanilla() {
        TotemDefinition found = byId.get(TotemDefinition.VANILLA_ID);
        return found != null ? found : defaultVanilla();
    }

    /** Every type, in the order the file declared them. */
    public Collection<TotemDefinition> all() {
        return byId.values();
    }

    public java.util.Set<String> ids() {
        return byId.keySet();
    }

    public int size() {
        return byId.size();
    }

    /**
     * The fallback vanilla entry: ordinary stack size, no cooldown, no custom effects, no skills,
     * no permission.
     *
     * <p>Kept identical to what a server owner would get from an empty {@code vanilla:} section, so
     * deleting that section from the file changes nothing.
     */
    private static TotemDefinition defaultVanilla() {
        return new TotemDefinition(
                TotemDefinition.VANILLA_ID,
                null,
                List.of(),
                "vanilla:TOTEM_OF_UNDYING",
                1,
                TotemDefinition.NO_COOLDOWN, // the plain totem is always ready, exactly like vanilla
                null, // item-model: leave the vanilla totem looking like the vanilla totem
                null, // custom-model-data: likewise
                List.of(),
                List.of(), // skills: a plain totem casts nothing, with or without MythicMobs
                true,
                false,
                null);
    }
}
