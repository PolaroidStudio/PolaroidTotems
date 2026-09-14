package me.juancayc.polaroidtotems.storage;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for per-player totem cooldowns, with no JDBC in its signatures.
 *
 * <p>Backend-agnostic by construction: nothing here mentions a {@link java.sql.Connection}, a
 * dialect or a table, and every failure arrives as {@link StorageException}. That is what lets the
 * SQLite and MySQL branches differ only in two SQL fragments (see {@link SqlCooldownRepository}) and
 * lets the cache above it be tested against a hand-written in-memory implementation.
 *
 * <h2>Every method here BLOCKS</h2>
 *
 * <p>JDBC is blocking, so every one of these is an off-main-thread call. Nothing on the resurrection
 * path may call any of them: that path reads {@link me.juancayc.polaroidtotems.totem.CooldownCache}
 * and nothing else. The only callers are the async join load, the timed flush and the disable flush.
 */
public interface CooldownRepository {

    /**
     * Creates the table and its indexes if they do not exist.
     *
     * <p>Called exactly ONCE, during {@code onEnable}, never from a reload. Re-running DDL on every
     * reload is how a plugin turns a config edit into a table lock.
     */
    void createSchema() throws StorageException;

    /**
     * Every cooldown stored for one player that has not already expired.
     *
     * <p>Scoped to one player and bounded by the clock, never a whole-table {@code SELECT *}: this
     * runs on join, and a server with ten thousand recorded players must not read ten thousand rows
     * to greet one of them. Expired rows are filtered in SQL rather than in Java so a player who has
     * been away for a month transfers nothing over the wire.
     *
     * @param player the player joining
     * @param now    epoch millis, passed in so the query is deterministic in tests
     */
    List<CooldownRow> loadActive(UUID player, long now) throws StorageException;

    /**
     * Writes every given row, inserting or replacing as needed, in ONE transaction.
     *
     * <p>Batched rather than one call per row because the flush timer wakes with every dirty row in
     * the server, and a per-row round trip under SQLite's single writer lock is precisely the stall
     * this design exists to avoid.
     *
     * <p>An empty collection is a no-op and must not open a connection.
     */
    void saveAll(Collection<CooldownRow> rows) throws StorageException;

    /**
     * Deletes rows that expired before the given instant.
     *
     * <p>Housekeeping, not correctness: an expired row is already treated as absent everywhere, so
     * this only stops the table growing without bound on a server that has been running for years.
     *
     * @return how many rows were removed
     */
    int purgeExpired(long now) throws StorageException;

    /** Closes the pool. Called exactly once, from {@code onDisable}, never from a reload. */
    void close();
}
