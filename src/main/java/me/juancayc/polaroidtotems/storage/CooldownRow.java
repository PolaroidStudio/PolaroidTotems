package me.juancayc.polaroidtotems.storage;

import java.util.UUID;

/**
 * One persisted cooldown: this player, this totem type, expiring at this instant.
 *
 * <p>The primary key is {@code (player, totemId)}, which is exactly the granularity the feature
 * promises — per player per totem type. A player on cooldown for {@code ember} is not on cooldown
 * for {@code guardian}, and two players never affect each other.
 *
 * @param player    the player's UUID, never their name: names are rented, UUIDs are not, and a
 *                  rename must not silently hand someone a fresh set of cooldowns
 * @param totemId   the totem type id, already normalized to lowercase by the config layer
 * @param expiresAt epoch millis at which this cooldown ends. Epoch millis rather than a
 *                  {@code TIMESTAMP} column because it is timezone-free, compares as an integer in
 *                  both dialects, and survives a server moving between regions unchanged
 */
public record CooldownRow(UUID player, String totemId, long expiresAt) {

    /**
     * Whether this row has already elapsed at the given instant.
     *
     * <p>The boundary is deliberately EXCLUSIVE: a cooldown whose expiry equals {@code now} is
     * expired, so a totem is usable the moment its stated remaining time reaches zero rather than
     * one millisecond later. That matches what the player was told — see {@link
     * me.juancayc.polaroidtotems.util.DurationFormat}, which never displays a live cooldown as 0s.
     *
     * @param now the clock reading to compare against, passed in rather than read from
     *            {@code System} so tests are deterministic
     */
    public boolean isExpired(long now) {
        return expiresAt <= now;
    }

    /** Milliseconds left, never negative. Zero means it is usable now. */
    public long remainingMillis(long now) {
        return Math.max(0L, expiresAt - now);
    }
}
