package me.juancayc.polaroidtotems.totem;

import me.juancayc.polaroidtotems.storage.CooldownRow;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every loaded player's cooldowns, in memory, as the single source of truth for the death path.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>A resurrection is decided inside {@link org.bukkit.event.entity.EntityResurrectEvent}, which is
 * SYNCHRONOUS: the server is waiting on the handler to learn whether the player dies. JDBC is
 * blocking. Those two facts cannot be reconciled — there is no way to ask a database "is this totem
 * on cooldown" from that event without parking the main thread on
 * {@code HikariDataSource.getConnection}, and under SQLite's single writer that park is however long
 * the current writer holds the lock.
 *
 * <p>So the database is never on that path. Rows are loaded asynchronously when a player joins,
 * every decision reads this map, a use writes through to this map immediately, and dirty rows are
 * flushed asynchronously on a timer and at shutdown. The resurrection path performs zero JDBC calls
 * and zero I/O of any kind.
 *
 * <h2>Failing OPEN</h2>
 *
 * <p>A player whose rows have not arrived yet — they joined microseconds ago, or the async load is
 * still in flight, or the load threw — is treated as having NO cooldowns rather than being refused.
 *
 * <p>That direction is deliberate. The two errors are not symmetrical: failing open costs the server
 * one totem that should have been on cooldown, in a window measured in milliseconds, for a player
 * who has just logged in and is standing at a spawn point. Failing closed kills a player who was
 * entitled to live, on a server whose database happened to be slow — and death is not refundable.
 * A cooldown is a balance mechanism, and no balance mechanism is worth an unearned death.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link ConcurrentHashMap} throughout, because the writers genuinely are on different threads:
 * the join load runs on an async scheduler thread, the resurrection write-through runs on the main
 * (or under Folia, the player's region) thread, and the flush reads from the async thread again.
 * Per-player maps are concurrent for the same reason. This is not defensive cargo-culting — it is
 * the actual access pattern.
 *
 * <p>The per-player entry is only ever REPLACED wholesale by the loader and mutated per-key
 * elsewhere, so no reader ever observes a half-built player.
 */
public final class CooldownCache {

    /**
     * Loaded players, each with their own map of totem id to expiry.
     *
     * <p>Absence of a player key means "not loaded", which is distinct from an empty map meaning
     * "loaded, no cooldowns". The difference is the whole fail-open rule: only the first case
     * bypasses the check.
     */
    private final Map<UUID, Map<String, Long>> byPlayer = new ConcurrentHashMap<>();

    /**
     * Players with at least one cooldown written since the last flush.
     *
     * <p>Tracked per PLAYER rather than per row: a player's whole map is small (one entry per totem
     * type they have used), so re-writing all of it is cheaper than the bookkeeping to track which
     * of their three rows changed, and it makes the flush idempotent under a race with a new write.
     */
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    /**
     * Replaces one player's cooldowns with what storage returned.
     *
     * <p>Called from the async join load. Replacing rather than merging is correct here because this
     * is the first thing that happens for that player in this session — but see the guard below:
     * this DOES merge in anything written while the load was in flight, which is exactly the
     * fail-open window. A player who died and burned a cooldown in the 40ms before their rows
     * arrived must not have that cooldown erased by the load that was already running.
     */
    public void load(UUID player, Collection<CooldownRow> rows) {
        Map<String, Long> loaded = new ConcurrentHashMap<>();
        for (CooldownRow row : rows) {
            loaded.put(normalize(row.totemId()), row.expiresAt());
        }

        Map<String, Long> raced = byPlayer.put(player, loaded);
        if (raced != null) {
            // Something wrote a cooldown for this player before their rows landed. Keep the LATER
            // expiry of the two: the in-flight write is newer information, and a stored row that is
            // further out is a cooldown the player has genuinely not served yet.
            raced.forEach((totemId, expiresAt) ->
                    loaded.merge(totemId, expiresAt, Math::max));
        }
    }

    /**
     * Forgets a player, returning what they had so a caller can flush it first.
     *
     * <p>Called on quit, AFTER their flush has completed. Purging before the flush would drop the
     * very rows that were about to be written.
     */
    public void unload(UUID player) {
        byPlayer.remove(player);
        dirty.remove(player);
    }

    /** Whether this player's rows have arrived. False means every check fails OPEN for them. */
    public boolean isLoaded(UUID player) {
        return byPlayer.containsKey(player);
    }

    /**
     * Milliseconds left on this player's cooldown for this totem type, or 0 when it is usable.
     *
     * <p>The one method the resurrection path calls, and the reason everything above it exists: it
     * is two hash lookups and a subtraction, with no allocation and no I/O.
     *
     * <p>Returns 0 — usable — for an unloaded player. See the class note on failing open.
     *
     * @param now epoch millis, passed in rather than read from {@code System} so the whole decision
     *            uses one consistent clock reading and tests are deterministic
     */
    public long remainingMillis(UUID player, String totemId, long now) {
        Map<String, Long> cooldowns = byPlayer.get(player);
        if (cooldowns == null) return 0L; // not loaded yet: fail open

        Long expiresAt = cooldowns.get(normalize(totemId));
        if (expiresAt == null) return 0L;

        // Exclusive boundary: a cooldown expiring exactly NOW is served, not pending. See
        // CooldownRow#isExpired for why the two agree.
        return expiresAt <= now ? 0L : expiresAt - now;
    }

    /** Convenience over {@link #remainingMillis}: true when this type may not save the player. */
    public boolean isOnCooldown(UUID player, String totemId, long now) {
        return remainingMillis(player, totemId, now) > 0L;
    }

    /**
     * Starts a cooldown for this player and totem type, in memory, immediately.
     *
     * <p>Write-through, not write-behind: the map is authoritative the instant this returns, so a
     * player who dies twice in the same second is refused the second time even though nothing has
     * reached the database yet. The flush is only about surviving a restart.
     *
     * <p>Called on the resurrection path, so it does no I/O and takes no lock beyond the map's own.
     *
     * @param expiresAt epoch millis at which the cooldown ends
     */
    public void put(UUID player, String totemId, long expiresAt) {
        // computeIfAbsent rather than get-then-put: this is the one place that can run for a player
        // whose load has not landed, and losing the write to a race would silently hand them a free
        // second use.
        byPlayer.computeIfAbsent(player, ignored -> new ConcurrentHashMap<>())
                .put(normalize(totemId), expiresAt);
        dirty.add(player);
    }

    /**
     * Takes every dirty player's rows and clears the dirty flags in one pass.
     *
     * <p>Claim-then-read, so a write landing DURING the flush re-marks its player and is picked up by
     * the next one rather than being lost — the flag is removed before the map is read, never after.
     *
     * <p>Expired rows are skipped: writing a row that is already elapsed costs a round trip to store
     * something {@link #remainingMillis} would ignore and {@code purgeExpired} would delete.
     *
     * @param now epoch millis
     * @return the rows to persist, possibly empty
     */
    public List<CooldownRow> drainDirty(long now) {
        List<CooldownRow> rows = new ArrayList<>();

        for (UUID player : Set.copyOf(dirty)) {
            // Cleared FIRST. If a resurrection writes a new cooldown between here and the read
            // below, that write re-adds the flag and the next flush catches it. Clearing afterwards
            // would instead erase that flag and lose the write until the player's next death.
            dirty.remove(player);

            Map<String, Long> cooldowns = byPlayer.get(player);
            if (cooldowns == null) continue; // quit and purged between the copy and here

            cooldowns.forEach((totemId, expiresAt) -> {
                if (expiresAt > now) rows.add(new CooldownRow(player, totemId, expiresAt));
            });
        }
        return rows;
    }

    /**
     * One player's rows, for the quit flush, without touching anyone else's dirty state.
     *
     * <p>Returns an empty list when that player is not dirty, so a quit that follows no death costs
     * nothing at all — which is most quits.
     */
    public List<CooldownRow> drainDirty(UUID player, long now) {
        if (!dirty.remove(player)) return List.of();

        Map<String, Long> cooldowns = byPlayer.get(player);
        if (cooldowns == null) return List.of();

        List<CooldownRow> rows = new ArrayList<>();
        cooldowns.forEach((totemId, expiresAt) -> {
            if (expiresAt > now) rows.add(new CooldownRow(player, totemId, expiresAt));
        });
        return rows;
    }

    /** Whether anything is waiting to be written. Lets the flush timer skip an empty tick entirely. */
    public boolean hasDirty() {
        return !dirty.isEmpty();
    }

    /** How many players are currently loaded. Diagnostics only. */
    public int loadedPlayers() {
        return byPlayer.size();
    }

    /**
     * Totem ids are normalized exactly as {@code TotemsConfig} normalizes the keys of
     * {@code totems.yml}, so a row read back from a database written by an older version — or by a
     * hand edit — matches the definition it belongs to.
     */
    private static String normalize(@Nullable String totemId) {
        return totemId == null ? "" : totemId.trim().toLowerCase(Locale.ROOT);
    }
}
