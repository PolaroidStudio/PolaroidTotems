package me.juancayc.polaroidtotems.domain;

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
 * Cooldown clamping and the arithmetic around it: pure, with no server behind it.
 *
 * <p>Worth pinning for the same reason {@link me.juancayc.polaroidtotems.item.TotemStackSizeTest}
 * pins stack sizes — the value comes from a file a human edits, and a bad one must be corrected at
 * parse time where the warning still names the entry. The failure mode here is subtler than a
 * rejected item, though: a negative cooldown that reached the expiry arithmetic would produce a
 * timestamp in the PAST, which reads as "already expired" and silently disables the very limit the
 * server owner was configuring.
 */
class TotemCooldownTest {

    private final List<String> warnings = new ArrayList<>();

    private long clamp(long configured) {
        return TotemDefinition.clampCooldownSeconds(configured, "ember", warnings::add);
    }

    /** A definition whose only interesting property is its cooldown. */
    private static TotemDefinition withCooldown(long seconds) {
        return new TotemDefinition("ember", null, List.of(), "vanilla:TOTEM_OF_UNDYING", 1,
                seconds, null, null, List.of(), List.of(), true, false, null);
    }

    @ParameterizedTest
    @ValueSource(longs = {1L, 30L, 300L, 3600L, 604800L, Long.MAX_VALUE})
    @DisplayName("any non-negative value passes through untouched and warns about nothing")
    void nonNegativeValuesAreUnchanged(long configured) {
        assertEquals(configured, clamp(configured));
        assertTrue(warnings.isEmpty(), "there is no upper bound: 'once a week' is a real design");
    }

    @Test
    @DisplayName("zero passes through as the documented 'no cooldown' value, silently")
    void zeroIsTheDisabledValue() {
        assertEquals(TotemDefinition.NO_COOLDOWN, clamp(0L));
        assertTrue(warnings.isEmpty(), "0 is how a server owner turns the feature off; it is not an error");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1L, -300L, Long.MIN_VALUE})
    @DisplayName("a negative clamps to zero and warns, naming the totem")
    void negativesClampToZero(long configured) {
        assertEquals(TotemDefinition.NO_COOLDOWN, clamp(configured));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("ember"),
                "the warning must name the totem so a server owner can find the entry");
    }

    @Test
    @DisplayName("an absent or zero cooldown means the type is always ready")
    void zeroMeansNoCooldown() {
        assertFalse(withCooldown(TotemDefinition.NO_COOLDOWN).hasCooldown());
        assertTrue(withCooldown(1L).hasCooldown());
    }

    @Test
    @DisplayName("seconds convert to milliseconds for the expiry arithmetic")
    void secondsBecomeMillis() {
        assertEquals(300_000L, withCooldown(300L).cooldownMillis());
        assertEquals(0L, withCooldown(TotemDefinition.NO_COOLDOWN).cooldownMillis());
    }

    @Test
    @DisplayName("an absurd cooldown saturates instead of wrapping into a negative expiry")
    void hugeCooldownSaturates() {
        // The failure this guards against: Long.MAX_VALUE * 1000 overflows to a NEGATIVE number,
        // which added to `now` produces an expiry in the past — so the most extreme cooldown anyone
        // could write would be the one that does nothing at all. Saturating keeps the direction of
        // the typo intact.
        assertEquals(Long.MAX_VALUE, withCooldown(Long.MAX_VALUE).cooldownMillis());
        assertTrue(withCooldown(Long.MAX_VALUE / 2L).cooldownMillis() > 0L);
    }

    @Test
    @DisplayName("clamping is idempotent: clamping a clamped value changes nothing further")
    void clampingIsIdempotent() {
        long once = clamp(-500L);
        warnings.clear();
        assertEquals(once, clamp(once));
        assertTrue(warnings.isEmpty());
    }
}
