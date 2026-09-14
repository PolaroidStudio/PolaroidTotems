package me.juancayc.polaroidtotems.config;

import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.totem.TotemRegistry;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config parsing, exercised through YamlConfiguration without a running server.
 *
 * <p>No fixture here declares an {@code effects:} list on purpose: resolving an effect name goes
 * through {@code Registry.EFFECT}, which only exists on a live server. The effect path is covered
 * by {@link me.juancayc.polaroidtotems.item.TotemStackSizeTest} at the record level and by the
 * server at runtime; everything else about the file is pure parsing and is checked here.
 */
class TotemsConfigTest {

    private static YamlConfiguration yaml(String content) throws InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(content);
        return cfg;
    }

    @Test
    @DisplayName("a totem parses every field it declares")
    void parsesAllFields() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  guardian:
                    item: 'nexo:guardian_totem'
                    stack-size: 4
                    display-name: '<#95d027>ɢᴜᴀʀᴅɪᴀɴ'
                    lore:
                      - 'first line'
                      - 'second line'
                    item-model: 'polaroid:item/guardian_totem'
                    custom-model-data: 1001
                    consume: false
                    heal-to-full: true
                    permission: 'polaroidtotems.type.guardian'
                """);

        TotemDefinition guardian = TotemsConfig.parse(cfg, warning -> {}).get("guardian");

        assertNotNull(guardian);
        assertEquals("nexo:guardian_totem", guardian.item());
        assertEquals(4, guardian.stackSize());
        assertEquals("<#95d027>ɢᴜᴀʀᴅɪᴀɴ", guardian.displayName());
        assertEquals(List.of("first line", "second line"), guardian.lore());
        assertEquals("polaroid:item/guardian_totem", guardian.itemModel());
        assertEquals(1001, guardian.customModelData());
        assertEquals(false, guardian.consume());
        assertEquals(true, guardian.healToFull());
        assertEquals("polaroidtotems.type.guardian", guardian.permission());
    }

    @Test
    @DisplayName("omitted keys fall back to the documented defaults")
    void defaultsApply() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  plain:
                    stack-size: 8
                """);

        TotemDefinition plain = TotemsConfig.parse(cfg, warning -> {}).get("plain");

        assertNotNull(plain);
        assertEquals("vanilla:TOTEM_OF_UNDYING", plain.item(),
                "a missing `item:` must expand to the vanilla totem, not stay the bare keyword");
        assertNull(plain.displayName());
        assertNull(plain.itemModel(), "an omitted item-model must leave the item's own model alone");
        assertNull(plain.customModelData());
        assertNull(plain.permission());
        assertTrue(plain.lore().isEmpty());
        assertTrue(plain.effects().isEmpty());
        assertEquals(true, plain.consume(), "a totem must be consumed unless the file says otherwise");
        assertEquals(false, plain.healToFull());
    }

    @Test
    @DisplayName("the bare keyword `vanilla` expands to the full material reference")
    void bareVanillaKeywordExpands() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  guardian:
                    item: vanilla
                """);

        assertEquals("vanilla:TOTEM_OF_UNDYING",
                TotemsConfig.parse(cfg, warning -> {}).get("guardian").item());
    }

    @Test
    @DisplayName("a blank item-model is read as absent, not as an empty key")
    void blankItemModelBecomesNull() throws Exception {
        // A server owner who writes the key and then empties it means "no model", not "the empty
        // key". Left as "" it would reach Key.key() and be refused there, costing a warning for
        // something that was never an error.
        YamlConfiguration cfg = yaml("""
                totems:
                  guardian:
                    item-model: '   '
                """);

        assertNull(TotemsConfig.parse(cfg, warning -> {}).get("guardian").itemModel());
    }

    @Test
    @DisplayName("an out-of-range stack-size is clamped and warned about, not rejected")
    void stackSizeIsClamped() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  toobig:
                    stack-size: 250
                  toosmall:
                    stack-size: 0
                """);

        List<String> warnings = new ArrayList<>();
        TotemRegistry registry = TotemsConfig.parse(cfg, warnings::add);

        assertEquals(TotemDefinition.MAX_STACK_SIZE, registry.get("toobig").stackSize());
        assertEquals(TotemDefinition.MIN_STACK_SIZE, registry.get("toosmall").stackSize());
        assertEquals(2, warnings.size(), "each clamped totem must produce exactly one warning");
    }

    @Test
    @DisplayName("ids are lowercased, so Guardian and guardian are one type")
    void idsAreLowercased() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  Guardian:
                    stack-size: 4
                """);

        TotemRegistry registry = TotemsConfig.parse(cfg, warning -> {});

        assertNotNull(registry.get("guardian"));
        assertEquals("guardian", registry.get("guardian").id());
    }

    @Test
    @DisplayName("the reserved vanilla entry is synthesized when the file omits it")
    void vanillaAlwaysExists() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  guardian:
                    stack-size: 4
                """);

        TotemRegistry registry = TotemsConfig.parse(cfg, warning -> {});

        assertNotNull(registry.vanilla());
        assertEquals(TotemDefinition.VANILLA_ID, registry.vanilla().id());
        assertEquals(2, registry.size(), "guardian plus the synthesized vanilla entry");
    }

    @Test
    @DisplayName("a declared vanilla entry wins over the synthesized one")
    void declaredVanillaIsKept() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  vanilla:
                    stack-size: 16
                """);

        assertEquals(16, TotemsConfig.parse(cfg, warning -> {}).vanilla().stackSize());
    }

    @Test
    @DisplayName("a missing totems section still yields a usable vanilla type, plus a warning")
    void missingSectionIsSurvivable() throws Exception {
        List<String> warnings = new ArrayList<>();
        TotemRegistry registry = TotemsConfig.parse(yaml("something-else: true"), warnings::add);

        assertEquals(1, warnings.size());
        assertNotNull(registry.vanilla());
        assertEquals(1, registry.size());
    }

    @Test
    @DisplayName("an entry that is not a section is skipped with a warning, not a crash")
    void nonSectionEntryIsSkipped() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  broken: 'this should have been a mapping'
                  guardian:
                    stack-size: 4
                """);

        List<String> warnings = new ArrayList<>();
        TotemRegistry registry = TotemsConfig.parse(cfg, warnings::add);

        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("broken"));
        assertNotNull(registry.get("guardian"), "one bad entry must not cost the good ones");
    }

    @Test
    @DisplayName("an unknown type id resolves to vanilla rather than to nothing")
    void unknownTypeFallsBackToVanilla() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  vanilla:
                    stack-size: 16
                """);

        TotemRegistry registry = TotemsConfig.parse(cfg, warning -> {});

        // A totem stamped with a type a later config removed must still behave like a totem.
        assertEquals(TotemDefinition.VANILLA_ID, registry.resolveOrVanilla("deleted_type").id());
        assertEquals(TotemDefinition.VANILLA_ID, registry.resolveOrVanilla(null).id());
    }

    @Test
    @DisplayName("a blank display-name or permission is read as absent, not as an empty string")
    void blankStringsBecomeNull() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  guardian:
                    display-name: ''
                    permission: '   '
                """);

        TotemDefinition guardian = TotemsConfig.parse(cfg, warning -> {}).get("guardian");

        assertNull(guardian.displayName());
        assertNull(guardian.permission(), "a blank permission must mean 'anyone', never 'permission \"\"'");
    }
}
