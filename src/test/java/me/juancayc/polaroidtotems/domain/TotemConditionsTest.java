package me.juancayc.polaroidtotems.domain;

import org.bukkit.World.Environment;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The condition predicate and its parser: pure, with no server behind it.
 *
 * <p>Both {@link DamageCause} and {@link Environment} are plain Java enums, so nothing here touches
 * a registry — the same reason {@link WorldBlacklistTest} can run without a server.
 *
 * <p>The failure this pins hardest is the silent widening one: a filter that fails OPEN turns a
 * void-only totem into a totem for every death on the server, which is a balance change nobody
 * would notice until players stop dying.
 */
class TotemConditionsTest {

    private final List<String> warnings = new ArrayList<>();

    private static YamlConfiguration yaml(String content) throws InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(content);
        return cfg;
    }

    private TotemConditions parse(String content) throws InvalidConfigurationException {
        return TotemConditions.parse(yaml(content).getConfigurationSection("conditions"), "voidwalker",
                warnings::add);
    }

    // --- matching ---------------------------------------------------------------------------

    @Test
    @DisplayName("no conditions match every cause in every environment, including unknown ones")
    void unrestrictedMatchesEverything() {
        TotemConditions any = TotemConditions.ANY;

        assertTrue(any.isUnrestricted());
        assertTrue(any.matches(DamageCause.LAVA, Environment.NORMAL));
        assertTrue(any.matches(DamageCause.VOID, Environment.THE_END));
        assertTrue(any.matches(null, null),
                "an unrestricted totem has no reason to care that the cause is unknown");
    }

    @Test
    @DisplayName("a cause filter admits only the listed causes")
    void causeFilter() {
        TotemConditions voidOnly = TotemConditions.of(Set.of(DamageCause.VOID), Set.of());

        assertTrue(voidOnly.matches(DamageCause.VOID, Environment.NORMAL));
        assertTrue(voidOnly.matches(DamageCause.VOID, Environment.THE_END),
                "no environment filter means any environment");
        assertFalse(voidOnly.matches(DamageCause.LAVA, Environment.THE_END));
        assertFalse(voidOnly.matches(null, Environment.THE_END),
                "a cause-restricted totem must never fire for a death whose cause is unknown");
    }

    @Test
    @DisplayName("an environment filter admits only the listed environments")
    void environmentFilter() {
        TotemConditions endOnly = TotemConditions.of(Set.of(), Set.of(Environment.THE_END));

        assertTrue(endOnly.matches(DamageCause.FALL, Environment.THE_END));
        assertFalse(endOnly.matches(DamageCause.FALL, Environment.NORMAL));
        assertFalse(endOnly.matches(DamageCause.FALL, null));
    }

    @Test
    @DisplayName("both filters must pass together")
    void bothFiltersAreAnded() {
        TotemConditions voidInEnd = TotemConditions.of(Set.of(DamageCause.VOID), Set.of(Environment.THE_END));

        assertTrue(voidInEnd.matches(DamageCause.VOID, Environment.THE_END));
        assertFalse(voidInEnd.matches(DamageCause.VOID, Environment.NORMAL), "right cause, wrong world");
        assertFalse(voidInEnd.matches(DamageCause.FALL, Environment.THE_END), "right world, wrong cause");
    }

    @Test
    @DisplayName("listsCause is strict: an empty filter lists nothing, even though it matches everything")
    void listsCauseIsStrict() {
        assertFalse(TotemConditions.ANY.listsCause(DamageCause.VOID),
                "otherwise every ordinary totem would become a void rescue");
        assertTrue(TotemConditions.of(Set.of(DamageCause.VOID), Set.of()).listsCause(DamageCause.VOID));
        assertFalse(TotemConditions.of(Set.of(DamageCause.VOID), Set.of()).listsCause(null));
    }

    @Test
    @DisplayName("the stored sets are immutable, so a snapshot cannot be edited after parsing")
    void setsAreImmutable() {
        TotemConditions conditions = TotemConditions.of(Set.of(DamageCause.VOID), Set.of(Environment.THE_END));

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> conditions.causes().add(DamageCause.LAVA));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> conditions.environments().clear());
    }

    // --- parsing ----------------------------------------------------------------------------

    @Test
    @DisplayName("an absent section parses to ANY, silently")
    void absentSectionIsAny() throws Exception {
        assertSame(TotemConditions.ANY, parse("something-else: true"));
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("names are read case-insensitively")
    void parsesCaseInsensitively() throws Exception {
        TotemConditions parsed = parse("""
                conditions:
                  causes: [void, Fall]
                  environments: [the_end]
                """);

        assertEquals(Set.of(DamageCause.VOID, DamageCause.FALL), parsed.causes());
        assertEquals(Set.of(Environment.THE_END), parsed.environments());
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("an unknown name costs that one entry and a warning naming it, never the rest")
    void unknownEntryIsSkipped() throws Exception {
        TotemConditions parsed = parse("""
                conditions:
                  causes: [VOID, VIOD]
                  environments: [THE_END, THE_MOON]
                """);

        assertEquals(Set.of(DamageCause.VOID), parsed.causes());
        assertEquals(Set.of(Environment.THE_END), parsed.environments());
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).contains("VIOD") && warnings.get(0).contains("voidwalker"));
        assertTrue(warnings.get(1).contains("THE_MOON"));
    }

    @Test
    @DisplayName("a filter whose every entry is a typo is OFF, and says so rather than failing silently")
    void allTyposWarnsThatTheFilterIsOff() throws Exception {
        TotemConditions parsed = parse("""
                conditions:
                  causes: [VIOD]
                """);

        assertTrue(parsed.causes().isEmpty());
        assertEquals(2, warnings.size(), "one for the bad entry, one that the filter is now off");
        assertTrue(warnings.get(1).contains("OFF"));
    }

    @Test
    @DisplayName("a single scalar is accepted as a one-entry list")
    void scalarIsAccepted() throws Exception {
        TotemConditions parsed = parse("""
                conditions:
                  causes: VOID
                """);

        assertEquals(Set.of(DamageCause.VOID), parsed.causes());
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("empty lists mean 'any', exactly like omitting the key")
    void emptyListsAreAny() throws Exception {
        TotemConditions parsed = parse("""
                conditions:
                  causes: []
                  environments: []
                """);

        assertTrue(parsed.isUnrestricted());
        assertTrue(warnings.isEmpty(), "an explicitly empty list is a choice, not a typo");
    }
}
