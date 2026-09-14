package me.juancayc.polaroidtotems.domain;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of the {@code skills:} list, exercised without a server.
 *
 * <p>Unlike {@code effects:}, this whole path is testable offline: a skill is a NAME plus two
 * numbers, and nothing here resolves it against MythicMobs. That is the point of keeping the record
 * Mythic-free — the validation a server owner actually trips over (a missing key, a typo'd number,
 * an entry written as a string instead of a mapping) is checked here rather than only at runtime.
 *
 * <p>The casting side is deliberately untested: it needs a live server with MythicMobs on it.
 */
class MythicSkillSpecTest {

    private final List<String> warnings = new ArrayList<>();

    /** Builds the totem section a real config would hand {@link MythicSkillSpec#parseList}. */
    private ConfigurationSection section(String content) throws InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(content);
        ConfigurationSection guardian = cfg.getConfigurationSection("guardian");
        assertNotNull(guardian, "the fixture itself must parse into a section");
        return guardian;
    }

    private List<MythicSkillSpec> parse(String content) throws InvalidConfigurationException {
        return MythicSkillSpec.parseList(section(content), "skills", warnings::add);
    }

    @Test
    @DisplayName("a skill parses every field it declares")
    void parsesAllFields() throws Exception {
        List<MythicSkillSpec> skills = parse("""
                guardian:
                  skills:
                    - skill: EclipseRevive
                      power: 1.5
                      target-self: false
                """);

        assertEquals(1, skills.size());
        assertEquals("EclipseRevive", skills.get(0).skillName());
        assertEquals(1.5f, skills.get(0).power());
        assertEquals(false, skills.get(0).targetSelf());
        assertTrue(warnings.isEmpty(), "a well-formed entry must not produce a warning");
    }

    @Test
    @DisplayName("omitted keys fall back to the documented defaults")
    void defaultsApply() throws Exception {
        List<MythicSkillSpec> skills = parse("""
                guardian:
                  skills:
                    - skill: EclipseRevive
                """);

        assertEquals(1, skills.size());
        assertEquals(1.0f, skills.get(0).power(), "1.0 is Mythic's neutral power multiplier");
        assertEquals(true, skills.get(0).targetSelf(),
                "a totem skill targets the player it just saved unless the file says otherwise");
    }

    @Test
    @DisplayName("an entry with no `skill:` key is skipped with a warning, not a crash")
    void missingSkillNameIsSkipped() throws Exception {
        List<MythicSkillSpec> skills = parse("""
                guardian:
                  skills:
                    - power: 2.0
                    - skill: EclipseRevive
                """);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("guardian"),
                "the warning must name the totem so a server owner can find the entry");
        assertEquals(1, skills.size(), "one bad entry must not cost the good ones");
        assertEquals("EclipseRevive", skills.get(0).skillName());
    }

    @Test
    @DisplayName("a blank skill name is treated as missing rather than cast as the empty name")
    void blankSkillNameIsSkipped() throws Exception {
        List<MythicSkillSpec> skills = parse("""
                guardian:
                  skills:
                    - skill: '   '
                """);

        assertEquals(1, warnings.size());
        assertTrue(skills.isEmpty());
    }

    @Test
    @DisplayName("a skill name keeps its case but loses its surrounding whitespace")
    void skillNameIsTrimmedNotLowercased() throws Exception {
        // Mythic skill names are case-sensitive, unlike this plugin's own totem ids, so trimming is
        // the only normalisation that is safe to apply here.
        List<MythicSkillSpec> skills = parse("""
                guardian:
                  skills:
                    - skill: '  EclipseRevive  '
                """);

        assertEquals("EclipseRevive", skills.get(0).skillName());
    }

    @Test
    @DisplayName("an entry that is not a mapping is skipped with a warning")
    void nonMappingEntryIsSkipped() throws Exception {
        List<MythicSkillSpec> skills = parse("""
                guardian:
                  skills:
                    - 'EclipseRevive'
                    - skill: EclipseShockwave
                """);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("mapping"),
                "the warning must say what shape was expected, not just that something failed");
        assertEquals(1, skills.size());
        assertEquals("EclipseShockwave", skills.get(0).skillName());
    }

    @Test
    @DisplayName("a negative power is clamped to zero instead of inverting the skill's own maths")
    void negativePowerIsClamped() throws Exception {
        List<MythicSkillSpec> skills = parse("""
                guardian:
                  skills:
                    - skill: EclipseRevive
                      power: -3.0
                """);

        assertEquals(0.0f, skills.get(0).power());
    }

    @Test
    @DisplayName("an unparseable power falls back to the default rather than failing the entry")
    void unparseablePowerFallsBack() throws Exception {
        List<MythicSkillSpec> skills = parse("""
                guardian:
                  skills:
                    - skill: EclipseRevive
                      power: 'very strong'
                """);

        assertEquals(1.0f, skills.get(0).power());
        assertEquals(1, skills.size(), "a bad number costs the value, not the whole skill");
    }

    @Test
    @DisplayName("an absent skills key yields an empty list, not null")
    void missingKeyYieldsEmptyList() throws Exception {
        List<MythicSkillSpec> skills = parse("""
                guardian:
                  stack-size: 4
                """);

        assertTrue(skills.isEmpty());
        assertTrue(warnings.isEmpty(), "omitting an optional key is not a mistake to warn about");
    }

    @Test
    @DisplayName("a null section is survivable, so a caller never has to pre-check")
    void nullSectionYieldsEmptyList() {
        assertTrue(MythicSkillSpec.parseList(null, "skills", warnings::add).isEmpty());
        assertTrue(warnings.isEmpty());
    }
}
