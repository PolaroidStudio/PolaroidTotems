package me.juancayc.polaroidtotems.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the {@code mode:} key. Pure: no server, no YAML. */
class TotemModeTest {

    @Test
    @DisplayName("an absent or blank mode is resurrect, silently")
    void absentIsResurrect() {
        List<String> warnings = new ArrayList<>();

        assertEquals(TotemMode.RESURRECT, TotemMode.parse(null, "t", warnings::add));
        assertEquals(TotemMode.RESURRECT, TotemMode.parse("   ", "t", warnings::add));
        assertTrue(warnings.isEmpty(), "absence is the default, not a mistake");
    }

    @ParameterizedTest
    @ValueSource(strings = {"keep-inventory", "KEEP-INVENTORY", "keep_inventory", "KEEP_INVENTORY",
            "keep inventory", "  Keep-Inventory  "})
    @DisplayName("keep-inventory is read case-insensitively, with _ or space as the separator")
    void keepInventorySpellings(String raw) {
        List<String> warnings = new ArrayList<>();

        assertEquals(TotemMode.KEEP_INVENTORY, TotemMode.parse(raw, "t", warnings::add));
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("resurrect parses to itself")
    void resurrectParses() {
        assertEquals(TotemMode.RESURRECT, TotemMode.parse("Resurrect", "t", w -> {}));
    }

    @Test
    @DisplayName("an unknown mode warns, naming the totem and the value, and falls back to resurrect")
    void unknownWarns() {
        List<String> warnings = new ArrayList<>();

        assertEquals(TotemMode.RESURRECT, TotemMode.parse("keepinv", "keeper", warnings::add));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("keeper"));
        assertTrue(warnings.get(0).contains("keepinv"));
    }
}
