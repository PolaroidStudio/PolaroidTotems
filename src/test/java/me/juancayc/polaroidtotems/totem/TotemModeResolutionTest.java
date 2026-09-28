package me.juancayc.polaroidtotems.totem;

import me.juancayc.polaroidtotems.domain.TotemConditions;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.domain.TotemMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item-to-type rule both inventory searches go through ({@link TotemRegistry#resolveFor}).
 *
 * <p>This is the piece that keeps the two modes apart — a resurrection search must never see a
 * keeper, the death search must never spend a resurrect totem — and that decides which non-totem
 * items count at all. The search itself walks a live inventory; this rule is pure and is tested here.
 */
class TotemModeResolutionTest {

    private static TotemDefinition type(String id, TotemMode mode) {
        return new TotemDefinition(id, null, List.of(), "vanilla:TOTEM_OF_UNDYING", 1,
                TotemDefinition.NO_COOLDOWN, null, null, List.of(), List.of(), true, false, null,
                TotemConditions.ANY, false, mode, false);
    }

    private static TotemRegistry registry(TotemDefinition... definitions) {
        Map<String, TotemDefinition> byId = new LinkedHashMap<>();
        for (TotemDefinition definition : definitions) byId.put(definition.id(), definition);
        return TotemRegistry.of(byId);
    }

    private final TotemDefinition guardian = type("guardian", TotemMode.RESURRECT);
    private final TotemDefinition keeper = type("keeper", TotemMode.KEEP_INVENTORY);
    private final TotemRegistry registry = registry(guardian, keeper);

    @Test
    @DisplayName("the resurrection search never sees a keep-inventory totem")
    void resurrectSkipsKeeper() {
        assertNull(registry.resolveFor("keeper", true, TotemMode.RESURRECT));
        assertSame(guardian, registry.resolveFor("guardian", true, TotemMode.RESURRECT));
    }

    @Test
    @DisplayName("the keep-inventory search never spends a resurrect totem")
    void keeperSearchSkipsResurrect() {
        assertNull(registry.resolveFor("guardian", true, TotemMode.KEEP_INVENTORY));
        assertSame(keeper, registry.resolveFor("keeper", true, TotemMode.KEEP_INVENTORY));
    }

    @Test
    @DisplayName("a tagged keeper on another material (a Nexo item on PAPER) is still found")
    void taggedNonTotemMaterialResolves() {
        assertSame(keeper, registry.resolveFor("keeper", false, TotemMode.KEEP_INVENTORY));
    }

    @Test
    @DisplayName("an untagged Totem of Undying is still the vanilla type")
    void untaggedTotemIsVanilla() {
        assertSame(registry.vanilla(), registry.resolveFor(null, true, TotemMode.RESURRECT));
        assertSame(registry.vanilla(), registry.resolveFor("removed_type", true, TotemMode.RESURRECT),
                "a tag a later config removed falls back to vanilla, as resolveOrVanilla always did");
    }

    @Test
    @DisplayName("an untagged or unknown-tagged non-totem item is not a totem at all")
    void nonTotemWithoutKnownTagIsNothing() {
        // Without this rule the death search, which reads every stack, would take a stack of dirt
        // for a vanilla totem.
        assertNull(registry.resolve(null, false));
        assertNull(registry.resolve("removed_type", false));
        assertNull(registry.resolveFor(null, false, TotemMode.RESURRECT));
    }

    @Test
    @DisplayName("a vanilla entry switched to keep-inventory turns plain totems into keepers")
    void vanillaCanBeAKeeper() {
        TotemRegistry keeperVanilla = registry(type(TotemDefinition.VANILLA_ID, TotemMode.KEEP_INVENTORY));

        assertNull(keeperVanilla.resolveFor(null, true, TotemMode.RESURRECT));
        assertSame(keeperVanilla.vanilla(), keeperVanilla.resolveFor(null, true, TotemMode.KEEP_INVENTORY));
    }

    @Test
    @DisplayName("hasKeepInventoryTypes reflects the configured types")
    void hasKeepInventoryTypes() {
        assertTrue(registry.hasKeepInventoryTypes());
        assertFalse(registry(guardian).hasKeepInventoryTypes());
        assertFalse(TotemRegistry.empty().hasKeepInventoryTypes());
    }
}
