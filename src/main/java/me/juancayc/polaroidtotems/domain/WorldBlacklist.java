package me.juancayc.polaroidtotems.domain;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Where totems are not allowed to work, as {@code config.yml}'s {@code worlds:} section describes it.
 *
 * <h2>Why this is a snapshot and not a pair of config reads</h2>
 *
 * <p>This is consulted on the death path, inside {@link org.bukkit.event.entity.EntityResurrectEvent}
 * — an event that fires on every lethal hit taken by every entity on the server, not only the ones a
 * totem could save. Asking {@code FileConfiguration} for a section there means walking a YAML node
 * tree, allocating a {@code MemorySection} view and re-lowercasing every world name, on the main
 * thread, several times a second on a busy server.
 *
 * <p>So the file is parsed ONCE per reload into the two maps below and looked up as a hash set
 * membership test afterwards. The object is immutable and swapped wholesale by {@link
 * me.juancayc.polaroidtotems.config.ConfigManager} on reload, exactly like {@link
 * me.juancayc.polaroidtotems.totem.TotemRegistry} — so a resurrection happening mid-reload reads one
 * consistent snapshot rather than a half-rebuilt map.
 *
 * <h2>Case</h2>
 *
 * <p>World names are case-SENSITIVE on disk and in {@code World#getName}, but a server owner typing
 * one into a config file gets the case wrong constantly ({@code World_Nether}, {@code Spawn_Lobby}).
 * A blacklist that silently fails to apply because of a capital letter is a safety feature that does
 * not work, so both sides are normalized to lowercase: the configured names when they are parsed
 * here, and the live world name when it is tested. The cost is that a server running two worlds whose
 * names differ ONLY by case cannot blacklist one without the other — an acceptable trade for a rule
 * whose entire job is to be hard to misconfigure.
 *
 * <p>Totem ids use {@code trim().toLowerCase(Locale.ROOT)}, the exact normalization {@link
 * me.juancayc.polaroidtotems.config.TotemsConfig} applies to the keys of {@code totems.yml}. The two
 * have to agree or a per-totem entry would name a type that cannot exist.
 */
public final class WorldBlacklist {

    /** The empty blacklist: nothing is blocked anywhere. What an absent {@code worlds:} produces. */
    private static final WorldBlacklist EMPTY = new WorldBlacklist(Set.of(), Map.of());

    private final Set<String> blockedWorlds;
    private final Map<String, Set<String>> blockedTotemsByWorld;

    private WorldBlacklist(Set<String> blockedWorlds, Map<String, Set<String>> blockedTotemsByWorld) {
        this.blockedWorlds = blockedWorlds;
        this.blockedTotemsByWorld = blockedTotemsByWorld;
    }

    /** Nothing blocked anywhere. */
    public static WorldBlacklist empty() {
        return EMPTY;
    }

    /**
     * Builds a snapshot from already-normalized input.
     *
     * <p>Exists so a test can construct one without a {@link ConfigurationSection}, and so {@link
     * #parse} has one place that takes the defensive copies. Both arguments are re-normalized here
     * rather than trusted, because "already normalized" is a claim a caller can get wrong.
     */
    public static WorldBlacklist of(Collection<String> blockedWorlds,
                                    Map<String, ? extends Collection<String>> blockedTotemsByWorld) {
        Set<String> worlds = new LinkedHashSet<>();
        if (blockedWorlds != null) {
            for (String world : blockedWorlds) {
                String normalized = normalizeWorld(world);
                if (!normalized.isEmpty()) worlds.add(normalized);
            }
        }

        Map<String, Set<String>> perWorld = new LinkedHashMap<>();
        if (blockedTotemsByWorld != null) {
            blockedTotemsByWorld.forEach((world, totemIds) -> {
                String normalizedWorld = normalizeWorld(world);
                if (normalizedWorld.isEmpty() || totemIds == null) return;

                Set<String> ids = new LinkedHashSet<>();
                for (String totemId : totemIds) {
                    String normalizedId = normalizeTotemId(totemId);
                    if (!normalizedId.isEmpty()) ids.add(normalizedId);
                }
                // An entry that named a world but listed nothing usable is dropped rather than kept
                // as an empty set: `isBlocked` would answer the same either way, and an absent key
                // makes `isEmpty()` honest about whether this snapshot does anything at all.
                if (!ids.isEmpty()) perWorld.put(normalizedWorld, Set.copyOf(ids));
            });
        }

        return worlds.isEmpty() && perWorld.isEmpty()
                ? EMPTY
                : new WorldBlacklist(Set.copyOf(worlds), Map.copyOf(perWorld));
    }

    /**
     * Reads the {@code worlds:} section of a loaded configuration.
     *
     * <p>Defensive in the same way every other parser in this plugin is: a malformed entry costs a
     * warning and that entry, never an exception. This runs during {@code onEnable} and during
     * {@code /totems reload}, and a throw in either place would take down a feature the operator was
     * only trying to configure.
     *
     * @param worlds    the {@code worlds:} section, or null when the file omits it entirely
     * @param onWarning receives one human-readable line per rejected entry
     */
    public static WorldBlacklist parse(@Nullable ConfigurationSection worlds,
                                       Consumer<String> onWarning) {
        if (worlds == null) return EMPTY;

        List<String> globalRaw = worlds.getStringList("blacklist");
        if (globalRaw.isEmpty() && worlds.contains("blacklist") && !worlds.isList("blacklist")) {
            // `blacklist: world_nether` instead of a list. getStringList swallows that and hands back
            // an empty list, so without this check the operator's rule vanishes in silence — the one
            // failure mode a blacklist must never have.
            onWarning.accept("config.yml: 'worlds.blacklist' must be a list of world names; "
                    + "ignoring it. Write each world on its own '- ' line.");
        }

        Map<String, List<String>> perWorld = new LinkedHashMap<>();
        ConfigurationSection perTotem = worlds.getConfigurationSection("per-totem-blacklist");
        if (perTotem != null) {
            for (String worldKey : perTotem.getKeys(false)) {
                if (!perTotem.isList(worldKey)) {
                    onWarning.accept("config.yml: 'worlds.per-totem-blacklist." + worldKey
                            + "' must be a list of totem ids; skipping that world.");
                    continue;
                }
                perWorld.put(worldKey, perTotem.getStringList(worldKey));
            }
        } else if (worlds.contains("per-totem-blacklist")) {
            onWarning.accept("config.yml: 'worlds.per-totem-blacklist' must be a section mapping "
                    + "world names to lists of totem ids; ignoring it.");
        }

        return of(globalRaw, perWorld);
    }

    /**
     * Whether NO totem may work in this world.
     *
     * <p>Separate from {@link #isBlocked} so the caller can short-circuit the whole inventory search
     * instead of asking the same question once per slot — see {@link
     * me.juancayc.polaroidtotems.totem.TotemService#findUsableTotem}.
     *
     * @param worldName the live world name, in whatever case the server holds it
     */
    public boolean isWorldBlocked(@Nullable String worldName) {
        return !blockedWorlds.isEmpty() && blockedWorlds.contains(normalizeWorld(worldName));
    }

    /**
     * Whether this specific totem type is blocked in this world, by either rule.
     *
     * <p>The pure predicate the whole feature reduces to, and the only thing the resurrection path
     * actually calls. Takes plain strings rather than a {@code World} and a {@link TotemDefinition}
     * so it can be tested without a server.
     *
     * @param worldName the live world name, in whatever case the server holds it
     * @param totemId   the type id, in whatever case the caller holds it
     */
    public boolean isBlocked(@Nullable String worldName, @Nullable String totemId) {
        // Fast path for the overwhelmingly common case: no rules configured at all, so the two
        // lowercase allocations below are never paid on the death path of a default install.
        if (isEmpty()) return false;

        String world = normalizeWorld(worldName);
        if (blockedWorlds.contains(world)) return true;

        Set<String> blockedHere = blockedTotemsByWorld.get(world);
        return blockedHere != null && blockedHere.contains(normalizeTotemId(totemId));
    }

    /** True when no rule is configured, so every caller can skip the feature entirely. */
    public boolean isEmpty() {
        return blockedWorlds.isEmpty() && blockedTotemsByWorld.isEmpty();
    }

    /** The globally blocked world names, lowercased. Immutable; for tests and diagnostics. */
    public Set<String> blockedWorlds() {
        return blockedWorlds;
    }

    /** Lowercased world name to the lowercased totem ids blocked in it. Immutable. */
    public Map<String, Set<String>> blockedTotemsByWorld() {
        return blockedTotemsByWorld;
    }

    /**
     * Lowercases a world name for comparison. See the class note on case for why this is not
     * {@code trim()}-free: a trailing space in YAML is invisible and would silently break the rule.
     */
    private static String normalizeWorld(@Nullable String worldName) {
        return worldName == null ? "" : worldName.trim().toLowerCase(Locale.ROOT);
    }

    /** Exactly the normalization {@code TotemsConfig} applies to the keys of {@code totems.yml}. */
    private static String normalizeTotemId(@Nullable String totemId) {
        return totemId == null ? "" : totemId.trim().toLowerCase(Locale.ROOT);
    }
}
