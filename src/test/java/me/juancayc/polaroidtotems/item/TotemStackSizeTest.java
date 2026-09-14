package me.juancayc.polaroidtotems.item;

import me.juancayc.polaroidtotems.domain.TotemDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stack-size clamping: pure arithmetic with no server behind it.
 *
 * <p>Worth a test because the range is not ours to choose. {@code minecraft:max_stack_size} accepts
 * 1-99 inclusive and refuses anything outside that, so a config typo has to be corrected before it
 * reaches {@code ItemStack#setData} — otherwise every give of that type throws at runtime instead of
 * at parse time.
 */
class TotemStackSizeTest {

    private final List<String> warnings = new ArrayList<>();

    private int clamp(int configured) {
        return TotemDefinition.clampStackSize(configured, "guardian", warnings::add);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 16, 64, 98, 99})
    @DisplayName("a value inside 1..99 passes through untouched and warns about nothing")
    void inRangeValuesAreUnchanged(int configured) {
        assertEquals(configured, clamp(configured));
        assertTrue(warnings.isEmpty(), "a legal value must not produce a warning");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -64, Integer.MIN_VALUE})
    @DisplayName("zero and negatives clamp up to 1, never to a stack the component would refuse")
    void tooSmallClampsToMinimum(int configured) {
        assertEquals(TotemDefinition.MIN_STACK_SIZE, clamp(configured));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("guardian"),
                "the warning must name the totem so a server owner can find the entry");
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 128, 999, Integer.MAX_VALUE})
    @DisplayName("anything above 99 clamps down to 99")
    void tooLargeClampsToMaximum(int configured) {
        assertEquals(TotemDefinition.MAX_STACK_SIZE, clamp(configured));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("guardian"));
    }

    @Test
    @DisplayName("the clamped bounds are exactly the component's own legal range")
    void boundsMatchTheDataComponent() {
        assertEquals(1, TotemDefinition.MIN_STACK_SIZE);
        assertEquals(99, TotemDefinition.MAX_STACK_SIZE);
    }

    @Test
    @DisplayName("clamping is idempotent: clamping a clamped value changes nothing further")
    void clampingIsIdempotent() {
        int once = clamp(500);
        warnings.clear();
        assertEquals(once, clamp(once));
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("a definition reports whether it is the reserved vanilla type")
    void vanillaIdIsRecognised() {
        TotemDefinition vanilla = new TotemDefinition(TotemDefinition.VANILLA_ID, null, List.of(),
                "vanilla:TOTEM_OF_UNDYING", 16, null, null, List.of(), true, false, null);
        TotemDefinition custom = new TotemDefinition("guardian", null, List.of(),
                "vanilla:TOTEM_OF_UNDYING", 4, null, null, List.of(), true, true, null);

        assertTrue(vanilla.isVanilla());
        assertFalse(custom.isVanilla());
    }

    @Test
    @DisplayName("a definition's lore and effect lists are defensive copies")
    void listsAreImmutable() {
        List<String> mutableLore = new ArrayList<>(List.of("a line"));
        TotemDefinition definition = new TotemDefinition("guardian", null, mutableLore,
                "vanilla:TOTEM_OF_UNDYING", 4, null, null, List.of(), true, false, null);

        mutableLore.add("a line added after construction");

        assertEquals(1, definition.lore().size(),
                "mutating the caller's list must not reach inside the definition");
    }
}
