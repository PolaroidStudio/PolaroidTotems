package me.juancayc.polaroidtotems.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a remaining cooldown reads in chat.
 *
 * <p>Pure string building, but the rounding direction is a real decision rather than a detail, and
 * the boundaries between units are exactly where an off-by-one would produce {@code 60s} or
 * {@code 0m 1s} in front of a player.
 */
class DurationFormatTest {

    @ParameterizedTest
    @CsvSource({
            "1000,   1s",
            "45000,  45s",
            "59000,  59s",
            "60000,  1m",
            "90000,  1m 30s",
            "150000, 2m 30s",
            "600000, 10m",
            "3599000, 59m 59s",
            "3600000, 1h",
            "3900000, 1h 5m",
            "86400000, 1d",
            "97200000, 1d 3h"
    })
    @DisplayName("a duration reads as the largest two units that carry information")
    void formatsReadably(long millis, String expected) {
        assertEquals(expected, DurationFormat.remaining(millis));
    }

    @Test
    @DisplayName("a whole unit drops the trailing zero rather than printing '2h 0m'")
    void wholeUnitsDropTheZero() {
        // '2h 0m' reads like a rounding artefact; '2h' reads like an answer.
        assertEquals("2h", DurationFormat.remaining(7_200_000L));
        assertEquals("5m", DurationFormat.remaining(300_000L));
        assertEquals("3d", DurationFormat.remaining(259_200_000L));
    }

    @Test
    @DisplayName("never more than two units, however long the duration is")
    void neverMoreThanTwoUnits() {
        // 1d 3h 12m 7s is a number a player has to parse. The smaller units are dropped, which
        // under-promises — see the class note on why that direction is the safe one.
        long millis = (86_400L + 3 * 3600L + 12 * 60L + 7L) * 1000L;
        assertEquals("1d 3h", DurationFormat.remaining(millis));
    }

    @Test
    @DisplayName("a partial second rounds UP, so the number never under-promises")
    void partialSecondsRoundUp() {
        // The player retrying at the moment the message named must not be refused again. Rounding
        // up can only make the totem available slightly early, which nobody complains about.
        assertEquals("2s", DurationFormat.remaining(1500L));
        assertEquals("1s", DurationFormat.remaining(1L));
        assertEquals("1s", DurationFormat.remaining(999L));
        assertEquals("1m", DurationFormat.remaining(59_001L));
    }

    @Test
    @DisplayName("zero and negatives read as 0s instead of throwing or printing a minus sign")
    void zeroAndNegativesAreSafe() {
        // A negative is not an error: it is what a clock past the expiry computes, and the caller is
        // about to stop showing the value anyway.
        assertEquals("0s", DurationFormat.remaining(0L));
        assertEquals("0s", DurationFormat.remaining(-1L));
        assertEquals("0s", DurationFormat.remaining(Long.MIN_VALUE));
        assertEquals("0s", DurationFormat.remainingSeconds(0L));
        assertEquals("0s", DurationFormat.remainingSeconds(-10L));
    }

    @Test
    @DisplayName("formatting from whole seconds matches formatting from the equivalent millis")
    void secondsAndMillisAgree() {
        // The seconds entry point exists so a configured cooldown never round-trips through millis
        // and risks the saturation guard. The two must still agree wherever both are valid.
        for (long seconds : new long[] {1L, 59L, 60L, 90L, 3600L, 86_400L, 97_200L}) {
            assertEquals(DurationFormat.remainingSeconds(seconds),
                    DurationFormat.remaining(seconds * 1000L),
                    "disagreement at " + seconds + "s");
        }
    }

    @Test
    @DisplayName("an enormous duration formats rather than overflowing into a negative")
    void hugeDurationsAreSafe() {
        // A real bug this pins down. Reachable, because TotemDefinition#cooldownMillis saturates to
        // exactly Long.MAX_VALUE for an absurd configured value — and the obvious ceiling division
        // `(millis + 999) / 1000` overflows there, wrapping to a negative and printing
        // '-106751991167d -7h' at a player. The digits below are the correct, positive answer.
        assertEquals("106751991167d 7h", DurationFormat.remaining(Long.MAX_VALUE));
    }
}
