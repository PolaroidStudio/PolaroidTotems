package me.juancayc.polaroidtotems.listeners;

import me.juancayc.polaroidtotems.totem.CooldownService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Brings a player's cooldowns into memory when they join and writes them out when they leave.
 *
 * <p>This listener is the reason the resurrection path can be free of JDBC: every database read for
 * a player happens here, once, at a moment when a few hundred milliseconds cost nothing — rather
 * than inside the synchronous event that decides whether they die.
 *
 * <p>Both handlers are MONITOR and return immediately. The actual work is handed to the async
 * scheduler by {@link CooldownService}, so neither the join nor the quit is delayed by a query.
 */
public final class CooldownListener implements Listener {

    private final CooldownService cooldowns;

    public CooldownListener(CooldownService cooldowns) {
        this.cooldowns = cooldowns;
    }

    /**
     * Starts the async load of this player's stored cooldowns.
     *
     * <p>Between this event and the load landing, the player is treated as having no cooldowns — the
     * fail-open window documented on {@link me.juancayc.polaroidtotems.totem.CooldownCache}. It is a
     * few milliseconds during which the player is standing at a spawn point, and the alternative
     * (blocking the join on a query) is strictly worse.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        cooldowns.loadAsync(event.getPlayer().getUniqueId());
    }

    /**
     * Flushes this player's dirty rows, then forgets them.
     *
     * <p>The order is enforced inside the service: the cache entry is purged in the continuation of
     * the write, never before it. Purging first would drop exactly the cooldown the player just
     * burned before logging off — which is also the one they would most like to have lost.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.flushAndUnloadAsync(event.getPlayer().getUniqueId());
    }
}
