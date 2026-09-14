package me.juancayc.polaroidtotems.totem;

import me.juancayc.polaroidtotems.storage.CooldownRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-memory cooldown state: the only thing the resurrection path is allowed to read.
 *
 * <p>Deliberately free of Bukkit and JDBC, which is the whole reason {@link CooldownCache} is a
 * separate class from {@link CooldownService}. The service owns the permission checks and the
 * schedulers, both of which need a server; the cache owns the decision, and every edge case worth
 * getting right lives here.
 *
 * <p>Every method takes {@code now} as a parameter rather than reading the clock, so these tests are
 * deterministic and the expiry boundary can be hit exactly rather than approximately.
 */
class CooldownCacheTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long NOW = 1_700_000_000_000L;

    private final CooldownCache cache = new CooldownCache();

    // ----- the fail-open rule ---------------------------------------------------------------------

    @Test
    @DisplayName("a player whose rows have not loaded yet has NO cooldown, rather than being refused")
    void unloadedPlayerFailsOpen() {
        // The rule that matters most in this file. A player who joined microseconds ago, or whose
        // async load is still in flight, must be savable: failing open costs the server one totem
        // that should have waited, while failing closed costs the player a death they had earned
        // the right to survive. Death is not refundable.
        assertFalse(cache.isLoaded(PLAYER));
        assertEquals(0L, cache.remainingMillis(PLAYER, "ember", NOW));
        assertFalse(cache.isOnCooldown(PLAYER, "ember", NOW));
    }

    @Test
    @DisplayName("a loaded player with no rows is distinct from an unloaded one, and equally usable")
    void loadedButEmptyIsUsable() {
        cache.load(PLAYER, List.of());

        assertTrue(cache.isLoaded(PLAYER), "an empty load is still a load; the distinction drives fail-open");
        assertEquals(0L, cache.remainingMillis(PLAYER, "ember", NOW));
    }

    // ----- the expiry boundary --------------------------------------------------------------------

    @Test
    @DisplayName("a cooldown expiring exactly NOW is usable, not pending")
    void exactlyExpiredIsUsable() {
        // The boundary is exclusive, and it has to be: the player was told a remaining time, and the
        // moment that time reaches zero the totem must work. One millisecond of disagreement here is
        // a player watching a '0s' message and still dying.
        cache.put(PLAYER, "ember", NOW);

        assertEquals(0L, cache.remainingMillis(PLAYER, "ember", NOW));
        assertFalse(cache.isOnCooldown(PLAYER, "ember", NOW));
    }

    @Test
    @DisplayName("one millisecond before expiry is still on cooldown")
    void justBeforeExpiryIsBlocked() {
        cache.put(PLAYER, "ember", NOW + 1L);

        assertEquals(1L, cache.remainingMillis(PLAYER, "ember", NOW));
        assertTrue(cache.isOnCooldown(PLAYER, "ember", NOW));
    }

    @Test
    @DisplayName("a cooldown already in the past reads as usable rather than as a negative")
    void pastExpiryIsUsable() {
        cache.put(PLAYER, "ember", NOW - 60_000L);

        assertEquals(0L, cache.remainingMillis(PLAYER, "ember", NOW),
                "a negative remaining time must never reach the formatter");
    }

    // ----- scope ----------------------------------------------------------------------------------

    @Test
    @DisplayName("a cooldown is per player per totem type, never shared")
    void cooldownsAreScopedPerPlayerPerType() {
        cache.load(PLAYER, List.of());
        cache.load(OTHER, List.of());
        cache.put(PLAYER, "ember", NOW + 300_000L);

        assertTrue(cache.isOnCooldown(PLAYER, "ember", NOW));
        assertFalse(cache.isOnCooldown(PLAYER, "guardian", NOW),
                "a player waiting on one type must still be saved by another");
        assertFalse(cache.isOnCooldown(OTHER, "ember", NOW),
                "one player's cooldown must never reach another");
    }

    @Test
    @DisplayName("totem ids are matched case-insensitively, as totems.yml lowercases its keys")
    void totemIdsAreNormalized() {
        cache.put(PLAYER, "Ember", NOW + 300_000L);

        assertTrue(cache.isOnCooldown(PLAYER, "ember", NOW));
        assertTrue(cache.isOnCooldown(PLAYER, "EMBER", NOW));
    }

    // ----- loading --------------------------------------------------------------------------------

    @Test
    @DisplayName("loading brings stored rows into memory")
    void loadPopulatesFromStorage() {
        cache.load(PLAYER, List.of(
                new CooldownRow(PLAYER, "ember", NOW + 300_000L),
                new CooldownRow(PLAYER, "guardian", NOW + 60_000L)));

        assertEquals(300_000L, cache.remainingMillis(PLAYER, "ember", NOW));
        assertEquals(60_000L, cache.remainingMillis(PLAYER, "guardian", NOW));
    }

    @Test
    @DisplayName("a cooldown written during the fail-open window survives the load that lands after it")
    void loadDoesNotEraseAnInFlightWrite() {
        // The exact race the fail-open rule creates: the player died and burned a cooldown in the
        // milliseconds before their rows arrived. A load that simply replaced the map would hand
        // them that use back for free.
        cache.put(PLAYER, "ember", NOW + 300_000L);
        cache.load(PLAYER, List.of(new CooldownRow(PLAYER, "guardian", NOW + 60_000L)));

        assertEquals(300_000L, cache.remainingMillis(PLAYER, "ember", NOW),
                "the write made during the load must not be lost");
        assertEquals(60_000L, cache.remainingMillis(PLAYER, "guardian", NOW));
    }

    @Test
    @DisplayName("when a load and an in-flight write disagree, the later expiry wins")
    void laterExpiryWinsOnRace() {
        cache.put(PLAYER, "ember", NOW + 60_000L);
        cache.load(PLAYER, List.of(new CooldownRow(PLAYER, "ember", NOW + 300_000L)));

        assertEquals(300_000L, cache.remainingMillis(PLAYER, "ember", NOW),
                "a stored row further out is a cooldown the player has genuinely not served");
    }

    // ----- dirty tracking and flushing ------------------------------------------------------------

    @Test
    @DisplayName("a load marks nothing dirty: those rows came FROM storage")
    void loadingDoesNotMarkDirty() {
        cache.load(PLAYER, List.of(new CooldownRow(PLAYER, "ember", NOW + 300_000L)));

        assertFalse(cache.hasDirty(), "re-writing what was just read is a round trip for nothing");
        assertTrue(cache.drainDirty(NOW).isEmpty());
    }

    @Test
    @DisplayName("a write marks the player dirty and drains into one row")
    void writeMarksDirty() {
        cache.put(PLAYER, "ember", NOW + 300_000L);
        assertTrue(cache.hasDirty());

        List<CooldownRow> drained = cache.drainDirty(NOW);

        assertEquals(1, drained.size());
        assertEquals(PLAYER, drained.get(0).player());
        assertEquals("ember", drained.get(0).totemId());
        assertEquals(NOW + 300_000L, drained.get(0).expiresAt());
    }

    @Test
    @DisplayName("draining clears the dirty flags, so an idle flush writes nothing twice")
    void drainingClearsDirty() {
        cache.put(PLAYER, "ember", NOW + 300_000L);
        cache.drainDirty(NOW);

        assertFalse(cache.hasDirty());
        assertTrue(cache.drainDirty(NOW).isEmpty());
        assertTrue(cache.isOnCooldown(PLAYER, "ember", NOW),
                "flushing persists the row; it does not forget it");
    }

    @Test
    @DisplayName("an expired row is not written: storage would only have to purge it again")
    void expiredRowsAreNotFlushed() {
        cache.put(PLAYER, "ember", NOW - 1L);

        assertTrue(cache.drainDirty(NOW).isEmpty());
    }

    @Test
    @DisplayName("draining one player leaves everyone else's dirty state alone")
    void perPlayerDrainIsScoped() {
        cache.put(PLAYER, "ember", NOW + 300_000L);
        cache.put(OTHER, "guardian", NOW + 300_000L);

        List<CooldownRow> drained = cache.drainDirty(PLAYER, NOW);

        assertEquals(1, drained.size());
        assertEquals(PLAYER, drained.get(0).player());
        assertTrue(cache.hasDirty(), "the other player must still be waiting to be flushed");
    }

    @Test
    @DisplayName("draining a player who is not dirty costs nothing, which is most quits")
    void cleanPlayerDrainsToNothing() {
        cache.load(PLAYER, List.of(new CooldownRow(PLAYER, "ember", NOW + 300_000L)));

        assertTrue(cache.drainDirty(PLAYER, NOW).isEmpty());
    }

    // ----- unloading ------------------------------------------------------------------------------

    @Test
    @DisplayName("unloading forgets a player entirely, returning them to the fail-open path")
    void unloadForgetsThePlayer() {
        cache.put(PLAYER, "ember", NOW + 300_000L);
        cache.unload(PLAYER);

        assertFalse(cache.isLoaded(PLAYER));
        assertEquals(0L, cache.remainingMillis(PLAYER, "ember", NOW));
        assertFalse(cache.hasDirty(), "unloading must not leave a dirty flag pointing at nothing");
    }

    @Test
    @DisplayName("loadedPlayers counts what is actually held")
    void loadedPlayersIsAccurate() {
        assertEquals(0, cache.loadedPlayers());
        cache.load(PLAYER, List.of());
        cache.load(OTHER, List.of());
        assertEquals(2, cache.loadedPlayers());
        cache.unload(PLAYER);
        assertEquals(1, cache.loadedPlayers());
    }

    // ----- the row record itself ------------------------------------------------------------------

    @Test
    @DisplayName("a row agrees with the cache about the expiry boundary")
    void rowBoundaryMatchesTheCache() {
        // The two must agree or a row loaded from storage would be judged differently from the same
        // cooldown written in memory.
        CooldownRow row = new CooldownRow(PLAYER, "ember", NOW);

        assertTrue(row.isExpired(NOW), "expiring exactly now is expired, in both places");
        assertEquals(0L, row.remainingMillis(NOW));
        assertFalse(new CooldownRow(PLAYER, "ember", NOW + 1L).isExpired(NOW));
        assertEquals(1L, new CooldownRow(PLAYER, "ember", NOW + 1L).remainingMillis(NOW));
        assertEquals(0L, new CooldownRow(PLAYER, "ember", NOW - 5_000L).remainingMillis(NOW),
                "remaining time is clamped at zero, never negative");
    }
}
