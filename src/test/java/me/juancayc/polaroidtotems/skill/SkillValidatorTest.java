package me.juancayc.polaroidtotems.skill;

import me.juancayc.polaroidtotems.domain.MythicSkillSpec;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.totem.TotemRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which configured skill names get reported, exercised without a server or MythicMobs.
 *
 * <p>This is the whole reason {@link SkillValidator#findUnknown} takes a {@link Predicate} instead
 * of reaching for the hook itself: the oracle is the only part that needs Mythic, so stubbing it
 * with a name set leaves the interesting half — walking the registry, caching per name, reporting
 * per totem — fully testable offline.
 *
 * <p>The reporting side ({@link SkillValidationReporter}) is deliberately untested here: it needs a
 * logger, a player list and a permission check, all of which only a live server provides.
 */
class SkillValidatorTest {

    /**
     * A stub oracle that knows a fixed set of names and counts how often it was asked.
     *
     * <p>Hand-written rather than mocked on purpose — the project pulls in no mocking framework,
     * and the whole contract here is two lines of behaviour.
     */
    private static final class RecordingOracle implements Predicate<String> {

        private final Set<String> known;
        private final List<String> asked = new ArrayList<>();

        RecordingOracle(String... known) {
            this.known = Set.of(known);
        }

        @Override
        public boolean test(String name) {
            asked.add(name);
            return known.contains(name);
        }
    }

    /** Builds a totem whose only interesting property is the skill names it declares. */
    private static TotemDefinition totem(String id, String... skillNames) {
        List<MythicSkillSpec> skills = new ArrayList<>();
        for (String name : skillNames) {
            skills.add(new MythicSkillSpec(name, 1.0f, true));
        }
        return new TotemDefinition(id, null, List.of(), "vanilla:TOTEM_OF_UNDYING", 1,
                TotemDefinition.NO_COOLDOWN, null, null, List.of(), skills, true, false, null);
    }

    private static TotemRegistry registryOf(TotemDefinition... definitions) {
        Map<String, TotemDefinition> byId = new LinkedHashMap<>();
        for (TotemDefinition definition : definitions) {
            byId.put(definition.id(), definition);
        }
        return TotemRegistry.of(byId);
    }

    @Test
    @DisplayName("a config whose every skill resolves produces no findings at all")
    void everyKnownNameIsClean() {
        SkillValidator.Report report = SkillValidator.findUnknown(
                registryOf(totem("guardian", "EclipseRevive"), totem("ember", "EmberBurst")),
                new RecordingOracle("EclipseRevive", "EmberBurst"));

        assertTrue(report.isClean(), "silence is the healthy state; a clean pass reports nothing");
        assertTrue(report.findings().isEmpty());
        assertEquals(2, report.checked());
    }

    @Test
    @DisplayName("an unknown name is reported with the totem that declares it")
    void unknownNameNamesItsTotem() {
        SkillValidator.Report report = SkillValidator.findUnknown(
                registryOf(totem("guardian", "EclipseRevive", "TypoedSkill")),
                new RecordingOracle("EclipseRevive"));

        assertEquals(1, report.findings().size());
        assertEquals("guardian", report.findings().get(0).totemId(),
                "the totem id is the actionable half: it is the entry the owner has to edit");
        assertEquals("TypoedSkill", report.findings().get(0).skillName());
    }

    @Test
    @DisplayName("one name shared by several totems is reported once per totem, asked once of Mythic")
    void sharedNameIsReportedPerTotemButAskedOnce() {
        RecordingOracle oracle = new RecordingOracle();
        SkillValidator.Report report = SkillValidator.findUnknown(
                registryOf(totem("guardian", "Missing"), totem("ember", "Missing"),
                        totem("frost", "Missing")),
                oracle);

        assertEquals(3, report.findings().size(),
                "each totem needs its own line, or the owner does not know where to fix it");
        assertEquals(List.of("guardian", "ember", "frost"),
                report.findings().stream().map(SkillValidator.Finding::totemId).toList());
        assertEquals(List.of("Missing"), oracle.asked,
                "the answer cannot change mid-pass, so the same name is put to Mythic once");
        assertEquals(1, report.checked(), "checked() counts distinct names, not references");
    }

    @Test
    @DisplayName("a registry with no totems of its own still validates cleanly")
    void emptyRegistryIsClean() {
        // Never truly empty: TotemRegistry always synthesizes the reserved vanilla entry, which
        // declares no skills. That is exactly the shape a server with a blank totems.yml has.
        SkillValidator.Report report =
                SkillValidator.findUnknown(TotemRegistry.empty(), new RecordingOracle());

        assertTrue(report.isClean());
        assertEquals(0, report.checked());
    }

    @Test
    @DisplayName("a null registry is survivable rather than a crash inside a scheduled task")
    void nullRegistryIsClean() {
        // Worth pinning: the startup path runs this from a scheduler callback a second after enable,
        // where an exception would be swallowed into a stack trace nobody connects to this feature.
        assertTrue(SkillValidator.findUnknown(null, new RecordingOracle()).isClean());
    }

    @Test
    @DisplayName("an oracle that throws is read as 'unknown' instead of aborting the whole pass")
    void throwingOracleDoesNotAbortThePass() {
        // Matches MythicSkillHook's catch(Throwable) doctrine: a third-party plugin misbehaving must
        // cost one answer, not every finding after it.
        SkillValidator.Report report = SkillValidator.findUnknown(
                registryOf(totem("guardian", "Explodes"), totem("ember", "AlsoMissing")),
                name -> {
                    if (name.equals("Explodes")) throw new LinkageError("version mismatch");
                    return false;
                });

        assertEquals(2, report.findings().size());
        assertEquals("Explodes", report.findings().get(0).skillName(),
                "a name we could not confirm is reported, not silently assumed valid");
    }

    @Test
    @DisplayName("findings keep the order the config declared them in")
    void findingsFollowConfigOrder() {
        SkillValidator.Report report = SkillValidator.findUnknown(
                registryOf(totem("guardian", "A", "B"), totem("ember", "C")),
                new RecordingOracle());

        assertEquals(List.of("A", "B", "C"),
                report.findings().stream().map(SkillValidator.Finding::skillName).toList());
    }

    @Test
    @DisplayName("a report's findings list is a defensive copy")
    void findingsAreImmutable() {
        List<SkillValidator.Finding> mutable =
                new ArrayList<>(List.of(new SkillValidator.Finding("guardian", "Missing")));
        SkillValidator.Report report = new SkillValidator.Report(1, mutable);

        mutable.add(new SkillValidator.Finding("ember", "AddedAfterConstruction"));

        assertEquals(1, report.findings().size());
    }

    @Test
    @DisplayName("a null findings list is normalised to an empty one rather than kept as null")
    void nullFindingsBecomeEmpty() {
        assertTrue(new SkillValidator.Report(0, null).isClean());
    }

    // ----- the Mythic-absent branch ---------------------------------------------------------------

    @Test
    @DisplayName("a config with no skills at all reports nothing worth saying without MythicMobs")
    void noConfiguredSkillsMeansNoInstallationMismatch() {
        // The silence contract: a server that never wanted the feature and never installed Mythic
        // must not be told anything on every single boot.
        assertFalse(SkillValidator.hasConfiguredSkills(TotemRegistry.empty()));
        assertFalse(SkillValidator.hasConfiguredSkills(registryOf(totem("guardian"))));
        assertFalse(SkillValidator.hasConfiguredSkills(null));
    }

    @Test
    @DisplayName("skills configured with MythicMobs absent is a mismatch worth one console line")
    void configuredSkillsWithoutMythicIsDetectable() {
        TotemRegistry registry = registryOf(totem("guardian", "EclipseRevive", "EmberBurst"),
                totem("ember", "EclipseRevive"));

        assertTrue(SkillValidator.hasConfiguredSkills(registry));
        assertEquals(Set.of("EclipseRevive", "EmberBurst"),
                SkillValidator.configuredSkillNames(registry),
                "the mismatch line counts distinct names, so the shared one is not double-counted");
    }
}
