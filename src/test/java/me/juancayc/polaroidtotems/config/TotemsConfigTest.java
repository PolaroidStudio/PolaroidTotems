package me.juancayc.polaroidtotems.config;

import me.juancayc.polaroidtotems.domain.TotemConditions;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.domain.TotemMode;
import me.juancayc.polaroidtotems.totem.TotemRegistry;
import org.bukkit.World;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    @DisplayName("conditions and rescue are parsed into the definition")
    void parsesConditionsAndRescue() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  voidwalker:
                    conditions:
                      causes: [VOID]
                      environments: [THE_END]
                    rescue:
                      return-to-safe-ground: true
                """);

        List<String> warnings = new ArrayList<>();
        TotemDefinition voidwalker = TotemsConfig.parse(cfg, warnings::add).get("voidwalker");

        assertNotNull(voidwalker);
        assertEquals(Set.of(DamageCause.VOID), voidwalker.conditions().causes());
        assertEquals(Set.of(World.Environment.THE_END), voidwalker.conditions().environments());
        assertTrue(voidwalker.returnToSafeGround());
        assertTrue(warnings.isEmpty(), "a well-formed void totem with a rescue warns about nothing");
    }

    @Test
    @DisplayName("omitting conditions and rescue keeps the old behaviour: any death, no teleport")
    void conditionsAndRescueDefaultOff() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  plain:
                    stack-size: 8
                """);

        TotemRegistry registry = TotemsConfig.parse(cfg, warning -> {});

        assertSame(TotemConditions.ANY, registry.get("plain").conditions());
        assertEquals(false, registry.get("plain").returnToSafeGround());
        assertSame(TotemConditions.ANY, registry.vanilla().conditions(),
                "the synthesized vanilla entry must stay unrestricted too");
        assertEquals(false, registry.vanilla().returnToSafeGround());
    }

    @Test
    @DisplayName("a typo in conditions costs that entry, not the totem type")
    void conditionTypoKeepsTheType() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  voidwalker:
                    conditions:
                      causes: [VOID, NOT_A_CAUSE]
                    rescue:
                      return-to-safe-ground: true
                """);

        List<String> warnings = new ArrayList<>();
        TotemDefinition voidwalker = TotemsConfig.parse(cfg, warnings::add).get("voidwalker");

        assertNotNull(voidwalker, "one bad value must not cost the whole type");
        assertEquals(Set.of(DamageCause.VOID), voidwalker.conditions().causes());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("NOT_A_CAUSE"));
    }

    @Test
    @DisplayName("a VOID totem without a rescue is kept but warned about")
    void voidWithoutRescueWarns() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  voidwalker:
                    conditions:
                      causes: [VOID]
                """);

        List<String> warnings = new ArrayList<>();
        TotemDefinition voidwalker = TotemsConfig.parse(cfg, warnings::add).get("voidwalker");

        assertNotNull(voidwalker);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("return-to-safe-ground"));
    }

    @Test
    @DisplayName("a conditions value that is not a section is ignored with a warning")
    void nonSectionConditionsIsIgnored() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  voidwalker:
                    conditions: VOID
                """);

        List<String> warnings = new ArrayList<>();
        TotemDefinition voidwalker = TotemsConfig.parse(cfg, warnings::add).get("voidwalker");

        assertSame(TotemConditions.ANY, voidwalker.conditions());
        assertEquals(1, warnings.size());
    }

    // ----- mode / keep-experience -----------------------------------------------------------------

    @Test
    @DisplayName("mode defaults to resurrect and keep-experience to false")
    void modeDefaultsToResurrect() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  plain:
                    stack-size: 2
                """);

        TotemDefinition plain = TotemsConfig.parse(cfg, warning -> {}).get("plain");

        assertNotNull(plain);
        assertEquals(TotemMode.RESURRECT, plain.mode());
        assertTrue(plain.resurrects());
        assertFalse(plain.keepExperience());
        assertEquals(TotemMode.RESURRECT, TotemRegistry.empty().vanilla().mode(),
                "the synthesized vanilla entry is an ordinary resurrect totem");
    }

    @Test
    @DisplayName("a keep-inventory totem parses its mode and keep-experience without warnings")
    void parsesKeepInventoryType() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  keeper:
                    mode: keep-inventory
                    keep-experience: true
                    stack-size: 4
                    cooldown: 1800
                    permission: 'polaroidtotems.type.keeper'
                    conditions:
                      causes: [VOID]
                """);

        List<String> warnings = new ArrayList<>();
        TotemDefinition keeper = TotemsConfig.parse(cfg, warnings::add).get("keeper");

        assertNotNull(keeper);
        assertEquals(TotemMode.KEEP_INVENTORY, keeper.mode());
        assertTrue(keeper.keepsInventory());
        assertFalse(keeper.resurrects());
        assertTrue(keeper.keepExperience());
        assertEquals(4, keeper.stackSize());
        assertEquals(1800L, keeper.cooldownSeconds());
        assertEquals("polaroidtotems.type.keeper", keeper.permission());
        assertTrue(keeper.consume());
        // A VOID keeper without a rescue is legitimate — it lets the player die anyway — so the
        // "saved while still in the void" warning must not fire for it.
        assertTrue(warnings.isEmpty(), "unexpected warnings: " + warnings);
    }

    @Test
    @DisplayName("an unknown mode warns and falls back to resurrect without losing the type")
    void unknownModeFallsBack() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  odd:
                    mode: keep-everything
                    stack-size: 3
                """);

        List<String> warnings = new ArrayList<>();
        TotemDefinition odd = TotemsConfig.parse(cfg, warnings::add).get("odd");

        assertNotNull(odd);
        assertEquals(TotemMode.RESURRECT, odd.mode());
        assertEquals(3, odd.stackSize());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("keep-everything"));
    }

    @Test
    @DisplayName("resurrection-only keys on a keep-inventory totem are dropped, one warning each")
    void keeperIgnoresResurrectionKeys() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  keeper:
                    mode: keep-inventory
                    consume: false
                    heal-to-full: true
                    rescue:
                      return-to-safe-ground: true
                    skills:
                      - skill: SomeSkill
                """);

        List<String> warnings = new ArrayList<>();
        TotemDefinition keeper = TotemsConfig.parse(cfg, warnings::add).get("keeper");

        assertNotNull(keeper);
        assertEquals(TotemMode.KEEP_INVENTORY, keeper.mode());
        assertTrue(keeper.consume(), "a keeper is always consumed");
        assertFalse(keeper.healToFull());
        assertFalse(keeper.returnToSafeGround());
        assertTrue(keeper.skills().isEmpty());
        assertEquals(4, warnings.size(), "one warning per ignored key: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("consume")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("heal-to-full")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("rescue.return-to-safe-ground")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("skills")));
    }

    @Test
    @DisplayName("keys that would do nothing anyway are not reported on a keep-inventory totem")
    void keeperNoOpKeysAreSilent() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  keeper:
                    mode: KEEP_INVENTORY
                    consume: true
                    heal-to-full: false
                    effects: []
                """);

        List<String> warnings = new ArrayList<>();
        TotemDefinition keeper = TotemsConfig.parse(cfg, warnings::add).get("keeper");

        assertNotNull(keeper);
        assertEquals(TotemMode.KEEP_INVENTORY, keeper.mode(), "the mode is read case-insensitively");
        assertTrue(warnings.isEmpty(), "unexpected warnings: " + warnings);
    }

    @Test
    @DisplayName("keep-experience on a resurrect totem warns and is ignored")
    void keepExperienceOnResurrectWarns() throws Exception {
        YamlConfiguration cfg = yaml("""
                totems:
                  guardian:
                    keep-experience: true
                    heal-to-full: true
                """);

        List<String> warnings = new ArrayList<>();
        TotemDefinition guardian = TotemsConfig.parse(cfg, warnings::add).get("guardian");

        assertNotNull(guardian);
        assertFalse(guardian.keepExperience());
        assertTrue(guardian.healToFull(), "the resurrect keys of a resurrect totem are untouched");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("keep-experience"));
    }
}
