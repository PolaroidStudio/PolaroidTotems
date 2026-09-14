package me.juancayc.polaroidtotems.totem;

import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.storage.CooldownRepository;
import me.juancayc.polaroidtotems.storage.CooldownRow;
import me.juancayc.polaroidtotems.storage.StorageException;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The cooldown feature, from the permission check down to the flush timer.
 *
 * <p>{@link CooldownCache} owns the in-memory state and {@link CooldownRepository} owns the
 * database; this class owns the POLICY that joins them — who is exempt, when a cooldown starts, and
 * when dirty rows are written.
 *
 * <h2>The bypass permissions</h2>
 *
 * <p>Two levels, checked in that order:
 *
 * <ul>
 *   <li>{@value #BYPASS_ALL} — exempt from every totem's cooldown.</li>
 *   <li>{@value #BYPASS_ALL}{@code .<totemid>} — exempt from one type's cooldown only.</li>
 * </ul>
 *
 * <p>The per-type node is a CHILD of the blanket one on purpose. A permission plugin's wildcard
 * ({@code polaroidtotems.cooldown.bypass.*}) then grants every type without the server owner having
 * to enumerate them, while the parent node alone stays a separate, deliberate grant. Both are
 * declared in paper-plugin.yml with {@code default: op}, so the feature is on for everyone until an
 * operator says otherwise.
 *
 * <p>A bypassing player is not merely allowed through the check: no cooldown is WRITTEN for them
 * either. Recording one would mean revoking the permission silently re-imposed a cooldown they had
 * been accruing invisibly for weeks, which is not what anyone reading the permission name expects.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #isBypassing} and {@link #startCooldown} run on the resurrection path and touch memory
 * only. Every database call is scheduled on {@link org.bukkit.Server#getAsyncScheduler()} — never
 * {@code BukkitScheduler}, which throws {@code UnsupportedOperationException} on Folia, and this
 * plugin declares {@code folia-supported: true}. The async scheduler is the right owner regardless:
 * JDBC belongs to no region and touches no entity.
 */
public final class CooldownService {

    /** Blanket exemption, and the parent node of every per-type one. */
    public static final String BYPASS_ALL = "polaroidtotems.cooldown.bypass";

    /**
     * How often dirty rows are written, in seconds.
     *
     * <p>Thirty seconds rather than the five a battle-pass would use, because the write rate here is
     * bounded by how often players DIE, not by how often they move — a busy server produces a
     * handful of dirty rows a minute, not thousands. The exposure is proportional: an unclean crash
     * can lose at most the cooldowns burned in the last half minute, which costs those players one
     * free totem use each.
     */
    private static final long FLUSH_INTERVAL_SECONDS = 30L;

    /**
     * How often elapsed rows are deleted, in seconds.
     *
     * <p>Hourly, and purely housekeeping: an expired row is already invisible to every read path, so
     * this only stops the table growing without bound on a server that has run for years. Running it
     * more often would spend write-lock time to reclaim bytes nobody is waiting on.
     */
    private static final long PURGE_INTERVAL_SECONDS = 3600L;

    private final Plugin plugin;
    private final CooldownCache cache;
    private final CooldownRepository repository;

    public CooldownService(Plugin plugin, CooldownCache cache, CooldownRepository repository) {
        this.plugin = plugin;
        this.cache = cache;
        this.repository = repository;
    }

    /**
     * The permission node exempting a player from ONE totem type's cooldown.
     *
     * <p>Static and pure so the node's shape is pinned by a test rather than by three string
     * concatenations scattered across the codebase.
     */
    public static String bypassPermission(String totemId) {
        return BYPASS_ALL + "." + (totemId == null ? "" : totemId.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Whether this player skips the cooldown for this type entirely.
     *
     * <p>On the resurrection path: a permission check and nothing else. Both nodes are asked because
     * a player may hold either.
     */
    public boolean isBypassing(Permissible player, TotemDefinition definition) {
        return player.hasPermission(BYPASS_ALL)
                || player.hasPermission(bypassPermission(definition.id()));
    }

    /**
     * Milliseconds this player must still wait before this type can save them, or 0 when it can.
     *
     * <p>The single question {@link TotemService} asks. A type with no cooldown configured, and a
     * player who bypasses it, both answer 0 without touching the cache at all.
     *
     * @param now epoch millis; one reading is taken per resurrection and threaded through so every
     *            totem in the inventory is judged against the same instant
     */
    public long remainingMillis(Player player, TotemDefinition definition, long now) {
        if (!definition.hasCooldown()) return 0L;
        if (isBypassing(player, definition)) return 0L;
        return cache.remainingMillis(player.getUniqueId(), definition.id(), now);
    }

    /**
     * Records that this type has just saved this player, if it has a cooldown at all.
     *
     * <p>Memory only. The row reaches the database on the next flush; nothing on the death path
     * waits for that.
     *
     * <p>A bypassing player gets no row — see the class note on why an exemption must not accrue
     * invisible cooldowns.
     */
    public void startCooldown(Player player, TotemDefinition definition, long now) {
        if (!definition.hasCooldown()) return;
        if (isBypassing(player, definition)) return;

        cache.put(player.getUniqueId(), definition.id(), now + definition.cooldownMillis());
    }

    /**
     * Loads one player's stored cooldowns, asynchronously.
     *
     * <p>Called from the join listener. Until this lands the player is treated as having no
     * cooldowns — see {@link CooldownCache}'s note on failing open, which is what makes it safe for
     * this to be async at all.
     *
     * <p>A failure is logged and swallowed: a database that is down must cost the cooldown feature,
     * not the player's ability to join and be saved by a totem.
     */
    public void loadAsync(UUID player) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                List<CooldownRow> rows = repository.loadActive(player, System.currentTimeMillis());
                cache.load(player, rows);
            } catch (StorageException failed) {
                plugin.getLogger().warning("Could not load cooldowns for " + player
                        + "; they will be treated as having none. " + failed.getMessage());
                // Deliberately NOT marked loaded on failure. Leaving the player absent from the
                // cache keeps them on the fail-open path rather than pinning an empty map that a
                // later successful retry would have to distinguish from real data.
            }
        });
    }

    /**
     * Flushes one player's rows and then forgets them, asynchronously.
     *
     * <p>Ordering is the whole point: the purge happens in the task's own continuation, AFTER the
     * write has returned, so a quit can never drop rows that had not been persisted. A player who
     * rejoins before the flush finishes simply reloads them from the database a moment later.
     */
    public void flushAndUnloadAsync(UUID player) {
        List<CooldownRow> rows = cache.drainDirty(player, System.currentTimeMillis());

        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            writeQuietly(rows, "on quit for " + player);
            cache.unload(player);
        });
    }

    /**
     * Starts the periodic flush and purge timers. Called once, from {@code onEnable}.
     *
     * <p>Async scheduler for both: they are pure JDBC and belong to no region. Neither is scheduled
     * with {@code Bukkit.getScheduler()}, which throws on Folia.
     */
    public void startTimers() {
        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (!cache.hasDirty()) return; // an idle server opens no connection at all
            writeQuietly(cache.drainDirty(System.currentTimeMillis()), "on the flush timer");
        }, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            try {
                repository.purgeExpired(System.currentTimeMillis());
            } catch (StorageException failed) {
                plugin.getLogger().warning("Could not purge expired cooldowns: " + failed.getMessage());
            }
        }, PURGE_INTERVAL_SECONDS, PURGE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Writes everything still dirty, SYNCHRONOUSLY, then closes the pool. Called from
     * {@code onDisable}.
     *
     * <p>Synchronous on purpose, and it is the one place in this class that blocks the calling
     * thread. The server is shutting down: scheduling this asynchronously would hand the work to an
     * executor that is about to be torn down, and the rows would be lost — which is the exact
     * failure the whole flush design exists to prevent.
     *
     * <p>Timers are not cancelled here; Paper cancels a disabling plugin's scheduled tasks itself.
     */
    public void flushAndClose() {
        writeQuietly(cache.drainDirty(System.currentTimeMillis()), "on shutdown");
        repository.close();
    }

    /**
     * Persists rows, turning a storage failure into one log line.
     *
     * <p>Every caller is a scheduler task or the shutdown path, and none of them has a recovery
     * beyond this. Letting a {@link StorageException} escape a scheduled task buys a stack trace
     * nobody connects to the cooldown feature and, on the disable path, an aborted shutdown step.
     */
    private void writeQuietly(@Nullable List<CooldownRow> rows, String when) {
        if (rows == null || rows.isEmpty()) return;
        try {
            repository.saveAll(rows);
        } catch (StorageException failed) {
            plugin.getLogger().warning("Could not save " + rows.size() + " cooldown row(s) "
                    + when + ": " + failed.getMessage());
        }
    }

    /** The cache behind this service. Exposed for the listener that loads and purges players. */
    public CooldownCache cache() {
        return cache;
    }

    /**
     * Loads every player already online.
     *
     * <p>Only matters on a reload-style enable (PlugMan, a dev server), where {@code onEnable} runs
     * with players already connected and no join event will ever fire for them. On an ordinary boot
     * the list is empty and this costs one iteration.
     */
    public void loadOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadAsync(player.getUniqueId());
        }
    }
}
