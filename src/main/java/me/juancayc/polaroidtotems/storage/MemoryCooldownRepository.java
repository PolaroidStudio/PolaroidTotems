package me.juancayc.polaroidtotems.storage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A repository that stores nothing beyond this server session.
 *
 * <p>The fallback used when the configured database cannot be opened — an unreachable MySQL, a
 * read-only plugins folder, a driver that failed to resolve. A server in that state must still get
 * totems, stack sizes, effects and skills: losing an entire plugin because one optional persistence
 * layer is down is a far worse outcome than losing cooldowns across restarts.
 *
 * <p>Making that fallback a real implementation rather than a null reference is what keeps the rest
 * of the plugin free of "is storage available" branches. {@link
 * me.juancayc.polaroidtotems.totem.CooldownService} cannot tell the difference, and cooldowns keep
 * working perfectly WITHIN the session — they simply reset when the server restarts, which is
 * exactly the behaviour the plugin had before persistence existed.
 *
 * <p>Also the substitute a test uses in place of JDBC, which is why it is a real class and not an
 * anonymous stub.
 *
 * <h2>Threading</h2>
 *
 * <p>Synchronized rather than concurrent-collection-based. Every caller is already off the main
 * thread and the call rate is a handful per minute, so the simplest correct thing is the right one
 * here — and unlike {@link me.juancayc.polaroidtotems.totem.CooldownCache}, nothing on the
 * resurrection path ever touches this.
 */
public final class MemoryCooldownRepository implements CooldownRepository {

    private final Map<UUID, Map<String, Long>> rows = new LinkedHashMap<>();

    @Override
    public void createSchema() {
        // Nothing to create. Declared rather than left to a default so the no-op is deliberate.
    }

    @Override
    public synchronized List<CooldownRow> loadActive(UUID player, long now) {
        Map<String, Long> forPlayer = rows.get(player);
        if (forPlayer == null) return List.of();

        List<CooldownRow> active = new ArrayList<>();
        forPlayer.forEach((totemId, expiresAt) -> {
            if (expiresAt > now) active.add(new CooldownRow(player, totemId, expiresAt));
        });
        return active;
    }

    @Override
    public synchronized void saveAll(Collection<CooldownRow> toSave) {
        if (toSave == null) return;
        for (CooldownRow row : toSave) {
            rows.computeIfAbsent(row.player(), ignored -> new LinkedHashMap<>())
                    .put(row.totemId(), row.expiresAt());
        }
    }

    @Override
    public synchronized int purgeExpired(long now) {
        int removed = 0;
        for (Map<String, Long> forPlayer : rows.values()) {
            var iterator = forPlayer.entrySet().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().getValue() <= now) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        rows.values().removeIf(forPlayer -> forPlayer.isEmpty());
        return removed;
    }

    @Override
    public synchronized void close() {
        rows.clear();
    }
}
