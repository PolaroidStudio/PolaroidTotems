package me.juancayc.polaroidtotems.totem;

import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.domain.TotemMode;
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

    /**
     * Whether any type is a keep-inventory one. Computed once per registry, because it is the
     * early exit of a listener that runs on EVERY player death on the server: a server that never
     * configured a keeper must pay one field read for it, not an inventory walk.
     */
    private final boolean hasKeepInventoryTypes;

    private TotemRegistry(Map<String, TotemDefinition> byId) {
        // Collections.unmodifiableMap over a LinkedHashMap, NOT Map.copyOf. Map.copyOf returns an
        // immutable HASH map, which discards insertion order entirely — so `all()` and `ids()` would
        // hand back totems in an arbitrary, JVM-dependent sequence despite what they promise, and
        // `/totems list` would shuffle its rows between restarts. The defensive copy is taken here
        // so the caller's map cannot be mutated into this one afterwards.
        this.byId = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        this.hasKeepInventoryTypes = this.byId.values().stream().anyMatch(TotemDefinition::keepsInventory);
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

    /**
     * Which type an item is, given what the caller already knows about it — or null when it is not
     * a totem of this plugin at all.
     *
     * <p>The rule, and why each branch exists:
     *
     * <ul>
     *   <li>A tag naming a KNOWN type is that type, whatever the item's material. This is what lets
     *       a keep-inventory totem be backed by something other than a Totem of Undying (a Nexo
     *       item on {@code PAPER}, say) and still be found.</li>
     *   <li>Otherwise a Totem of Undying is the reserved vanilla type — untagged or tagged with a
     *       type a later config removed, exactly as {@link #resolveOrVanilla} has always read it.</li>
     *   <li>Otherwise it is nothing. An untagged stick is NOT the vanilla type: that fallback only
     *       ever made sense for real totems, and without this branch a death search that reads
     *       every stack (the keep-inventory one) would take a stack of dirt for a vanilla totem.</li>
     * </ul>
     *
     * <p>Pure: the material test is passed in as a boolean, so this is testable without a server.
     *
     * @param tag           the PDC type tag read from the item, or null when it carries none
     * @param totemMaterial whether the item is a {@code TOTEM_OF_UNDYING}
     */
    public @Nullable TotemDefinition resolve(@Nullable String tag, boolean totemMaterial) {
        TotemDefinition tagged = get(tag);
        if (tagged != null) return tagged;
        return totemMaterial ? vanilla() : null;
    }

    /**
     * {@link #resolve}, narrowed to the types that serve one mode.
     *
     * <p>The single filter that keeps the two features apart: a resurrection search asks for {@link
     * TotemMode#RESURRECT} and never sees a keeper, the death search asks for {@link
     * TotemMode#KEEP_INVENTORY} and never spends a resurrect totem on keeping items.
     *
     * @return the type, or null when the item is not a totem or is a totem of the other mode
     */
    public @Nullable TotemDefinition resolveFor(@Nullable String tag, boolean totemMaterial, TotemMode wanted) {
        TotemDefinition definition = resolve(tag, totemMaterial);
        return definition != null && definition.mode() == wanted ? definition : null;
    }

    /** True when at least one configured type is a keep-inventory one. */
    public boolean hasKeepInventoryTypes() {
        return hasKeepInventoryTypes;
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
