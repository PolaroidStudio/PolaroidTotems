package me.juancayc.polaroidtotems.domain;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The blacklist predicate as a pure function, plus the parsing that feeds it.
 *
 * <p>Split the way {@link me.juancayc.polaroidtotems.skill.SkillValidator} was split from its
 * reporter: the decision "may this totem work here" needs no world, no player and no server, so it
 * is proven here exhaustively, and {@code TotemService} is left with nothing but the wiring.
 *
 * <p>The case-insensitivity cases carry the most weight. A blacklist whose rules silently fail to
 * apply because an operator typed {@code World_Nether} is worse than having no blacklist at all —
 * it looks configured and it is not.
 */
class WorldBlacklistTest {

    private static YamlConfiguration yaml(String content) throws InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(content);
        return cfg;
    }

    private static WorldBlacklist parse(String content) throws InvalidConfigurationException {
        return WorldBlacklist.parse(yaml(content).getConfigurationSection("worlds"), warning -> {});
    }

    // ----- the global rule ------------------------------------------------------------------------

    @Test
    @DisplayName("a world in the global blacklist blocks every totem in it")
    void globallyBlockedWorldBlocksEverything() throws Exception {
        WorldBlacklist blacklist = parse("""
                worlds:
                  blacklist:
                    - world_nether
                    - spawn_lobby
                """);

        assertTrue(blacklist.isWorldBlocked("world_nether"));
        assertTrue(blacklist.isBlocked("world_nether", "ember"));
        assertTrue(blacklist.isBlocked("world_nether", "guardian"));
        assertTrue(blacklist.isBlocked("spawn_lobby", "anything_at_all"));
    }

    @Test
    @DisplayName("a world nobody listed blocks nothing")
    void unlistedWorldAllowsEverything() throws Exception {
        WorldBlacklist blacklist = parse("""
                worlds:
                  blacklist:
                    - world_nether
                """);

        assertFalse(blacklist.isWorldBlocked("world"));
        assertFalse(blacklist.isBlocked("world", "ember"));
    }

    // ----- the per-totem rule ---------------------------------------------------------------------

    @Test
    @DisplayName("a totem listed for one world is blocked there and allowed everywhere else")
    void perTotemRuleIsScopedToItsWorld() throws Exception {
        WorldBlacklist blacklist = parse("""
                worlds:
                  per-totem-blacklist:
                    world_the_end:
                      - ember
                      - guardian
                    pvp_arena:
                      - frost
                """);

        assertTrue(blacklist.isBlocked("world_the_end", "ember"));
        assertTrue(blacklist.isBlocked("world_the_end", "guardian"));
        assertTrue(blacklist.isBlocked("pvp_arena", "frost"));

        assertFalse(blacklist.isBlocked("world_the_end", "frost"),
                "a type listed for another world must still work here");
        assertFalse(blacklist.isBlocked("world", "ember"),
                "the same type must be untouched in a world nobody listed");
        assertFalse(blacklist.isWorldBlocked("world_the_end"),
                "naming specific types must NOT make the world globally blocked");
    }

    @Test
    @DisplayName("the two rules compose: a global world blocks types its per-world list never named")
    void rulesCompose() throws Exception {
        WorldBlacklist blacklist = parse("""
                worlds:
                  blacklist:
                    - world_nether
                  per-totem-blacklist:
                    world_nether:
                      - ember
                """);

        assertTrue(blacklist.isBlocked("world_nether", "ember"));
        assertTrue(blacklist.isBlocked("world_nether", "guardian"),
                "the global rule wins regardless of what the per-world list names");
    }

    // ----- case -----------------------------------------------------------------------------------

    @Test
    @DisplayName("world names match regardless of case, on both sides of the comparison")
    void worldNamesAreCaseInsensitive() throws Exception {
        WorldBlacklist blacklist = parse("""
                worlds:
                  blacklist:
                    - World_Nether
                  per-totem-blacklist:
                    WORLD_THE_END:
                      - ember
                """);

        // Configured with capitals, asked in lowercase.
        assertTrue(blacklist.isWorldBlocked("world_nether"));
        assertTrue(blacklist.isBlocked("world_the_end", "ember"));

        // And the other direction: an operator who spelled it right, asked with the live name in a
        // different case than they typed.
        assertTrue(blacklist.isWorldBlocked("WORLD_NETHER"));
        assertTrue(blacklist.isBlocked("World_The_End", "ember"));
    }

    @Test
    @DisplayName("totem ids match regardless of case, exactly as totems.yml lowercases its keys")
    void totemIdsAreCaseInsensitive() throws Exception {
        WorldBlacklist blacklist = parse("""
                worlds:
                  per-totem-blacklist:
                    world_the_end:
                      - Ember
                      - GUARDIAN
                """);

        assertTrue(blacklist.isBlocked("world_the_end", "ember"));
        assertTrue(blacklist.isBlocked("world_the_end", "guardian"));
        assertTrue(blacklist.isBlocked("world_the_end", "EmBeR"));
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed, because YAML makes it invisible")
    void whitespaceIsTrimmed() {
        WorldBlacklist blacklist = WorldBlacklist.of(
                List.of("  world_nether  "),
                Map.of(" world_the_end ", List.of("  ember ")));

        assertTrue(blacklist.isWorldBlocked("world_nether"));
        assertTrue(blacklist.isBlocked("world_the_end", "ember"));
    }

    // ----- absent and malformed config ------------------------------------------------------------

    @Test
    @DisplayName("an absent worlds section blocks nothing and allocates nothing")
    void absentSectionBlocksNothing() throws Exception {
        WorldBlacklist blacklist = parse("something-else: true");

        assertTrue(blacklist.isEmpty());
        assertFalse(blacklist.isBlocked("world", "ember"));
        assertFalse(blacklist.isWorldBlocked("world"));
    }

    @Test
    @DisplayName("empty lists are the same as no lists")
    void emptyListsBlockNothing() throws Exception {
        WorldBlacklist blacklist = parse("""
                worlds:
                  blacklist: []
                  per-totem-blacklist: {}
                """);

        assertTrue(blacklist.isEmpty());
        assertFalse(blacklist.isBlocked("world", "ember"));
    }

    @Test
    @DisplayName("a null world or totem id is survivable rather than an NPE on the death path")
    void nullsAreSurvivable() throws Exception {
        WorldBlacklist blacklist = parse("""
                worlds:
                  blacklist:
                    - world_nether
                """);

        assertFalse(blacklist.isBlocked(null, "ember"),
                "a null world matches no rule, so nothing is blocked rather than everything");
        assertTrue(blacklist.isBlocked("world_nether", null),
                "the GLOBAL rule is about the world, so it holds whatever the totem id is");
        assertFalse(blacklist.isWorldBlocked(null));
    }

    @Test
    @DisplayName("a blacklist written as a bare string warns instead of vanishing silently")
    void scalarBlacklistWarns() throws Exception {
        List<String> warnings = new ArrayList<>();
        WorldBlacklist blacklist = WorldBlacklist.parse(
                yaml("""
                        worlds:
                          blacklist: world_nether
                        """).getConfigurationSection("worlds"),
                warnings::add);

        // The rule genuinely cannot be honoured — but it must not disappear without a word, because
        // the operator would believe their world was protected.
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("blacklist"));
        assertTrue(blacklist.isEmpty());
    }

    @Test
    @DisplayName("a per-world entry that is not a list costs that world, not the whole section")
    void malformedPerWorldEntryIsSkipped() throws Exception {
        List<String> warnings = new ArrayList<>();
        WorldBlacklist blacklist = WorldBlacklist.parse(
                yaml("""
                        worlds:
                          per-totem-blacklist:
                            broken: 'this should have been a list'
                            world_the_end:
                              - ember
                        """).getConfigurationSection("worlds"),
                warnings::add);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("broken"));
        assertTrue(blacklist.isBlocked("world_the_end", "ember"),
                "one bad entry must not cost the good ones");
    }

    @Test
    @DisplayName("a world listed with no usable totem ids is dropped rather than kept as empty")
    void emptyPerWorldListIsDropped() throws Exception {
        WorldBlacklist blacklist = parse("""
                worlds:
                  per-totem-blacklist:
                    world_the_end: []
                """);

        assertTrue(blacklist.isEmpty(), "an entry that blocks nothing must not make the snapshot 'configured'");
    }

    @Test
    @DisplayName("the exposed maps are immutable so a caller cannot edit the live snapshot")
    void snapshotIsImmutable() throws Exception {
        WorldBlacklist blacklist = parse("""
                worlds:
                  blacklist:
                    - world_nether
                  per-totem-blacklist:
                    world_the_end:
                      - ember
                """);

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> blacklist.blockedWorlds().add("sneaky"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> blacklist.blockedTotemsByWorld().clear());
    }
}
