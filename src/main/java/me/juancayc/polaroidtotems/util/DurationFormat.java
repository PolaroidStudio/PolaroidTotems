package me.juancayc.polaroidtotems.util;

/**
 * Renders a remaining duration the way a player reads it: {@code 2m 30s}, not {@code 150}.
 *
 * <p>Pure, static and Bukkit-free so the whole thing is unit-testable, for the same reason {@link
 * me.juancayc.polaroidtotems.skill.SkillValidator} was split from its reporter: this is the half
 * with edge cases, and none of them need a server to provoke.
 *
 * <h2>Why not Duration.toString or a library</h2>
 *
 * <p>{@link java.time.Duration#toString()} produces ISO-8601 ({@code PT2M30S}), which is correct and
 * unreadable in chat. A formatter is three lines; a dependency for three lines is not worth the jar.
 *
 * <h2>Rounding is deliberately UP</h2>
 *
 * <p>Remaining time is computed from millis and shown in seconds, so 1500ms left has to become
 * either "1s" or "2s". Rounding UP is the only honest choice: a player told "1s" who retries after
 * one second is refused again, and concludes the number is lying. Rounding up can only ever make the
 * totem available slightly before the message promised, which nobody complains about.
 *
 * <p>The corollary is that anything still on cooldown reads as at least {@code 1s} — a remaining
 * time is never displayed as {@code 0s}, which would read as "it is ready" next to a refusal.
 */
public final class DurationFormat {

    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE;
    private static final long SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR;

    private DurationFormat() {
    }

    /**
     * Formats a remaining duration in milliseconds as the largest two units that carry information.
     *
     * <p>Two units, never more: {@code 1d 3h} tells a player everything they act on, while
     * {@code 1d 3h 12m 7s} is a number they have to parse. The smaller units are dropped, not
     * rounded into the larger one — "1d 3h" under-promises, and see the class note on why that
     * direction is the safe one.
     *
     * <p>Zero and negatives produce {@code 0s}. A negative is not an error here: it is what a clock
     * that has already passed the expiry computes, and the caller (a cooldown that has just run out)
     * is about to stop showing the value anyway.
     */
    public static String remaining(long millis) {
        if (millis <= 0L) return "0s";

        // Ceiling division, not `millis / 1000`, and written WITHOUT the obvious `(millis + 999)`.
        // That form overflows for a duration within 999ms of Long.MAX_VALUE — which is reachable,
        // because TotemDefinition#cooldownMillis saturates to exactly Long.MAX_VALUE for an absurd
        // configured value. The overflow wraps to a negative and prints '-106751991167d -7h' at a
        // player. Dividing first and adjusting keeps every intermediate in range.
        long seconds = millis / 1000L;
        if (millis % 1000L != 0L) seconds++;

        return remainingSeconds(seconds);
    }

    /**
     * The same formatting from a whole number of seconds.
     *
     * <p>Exposed separately because a configured cooldown is already in seconds, so documenting one
     * in a message (or a future {@code /totems list} column) must not round-trip through millis and
     * risk the saturation guard in {@link
     * me.juancayc.polaroidtotems.domain.TotemDefinition#cooldownMillis()}.
     */
    public static String remainingSeconds(long totalSeconds) {
        if (totalSeconds <= 0L) return "0s";

        long days = totalSeconds / SECONDS_PER_DAY;
        long hours = (totalSeconds % SECONDS_PER_DAY) / SECONDS_PER_HOUR;
        long minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        long seconds = totalSeconds % SECONDS_PER_MINUTE;

        if (days > 0L) return join(days, "d", hours, "h");
        if (hours > 0L) return join(hours, "h", minutes, "m");
        if (minutes > 0L) return join(minutes, "m", seconds, "s");
        return seconds + "s";
    }

    /**
     * Joins the leading unit with the next one, dropping the second when it is zero.
     *
     * <p>{@code 2h 0m} reads like a rounding artefact; {@code 2h} reads like an answer.
     */
    private static String join(long major, String majorUnit, long minor, String minorUnit) {
        return minor > 0L
                ? major + majorUnit + " " + minor + minorUnit
                : major + majorUnit;
    }
}
