package me.juancayc.polaroidtotems.listeners;

import me.juancayc.polaroidtotems.totem.SafeGroundTracker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Feeds {@link SafeGroundTracker}: records where each player last stood on solid ground.
 *
 * <p>{@code PlayerMoveEvent} is one of the hottest events on a server — it fires for head rotation
 * alone, many times a second per player. So the handler's first act is the cheapest possible
 * rejection: if the player did not cross into a different BLOCK, nothing about "which block are they
 * standing on" can have changed, and it returns before touching the world at all. Only the rare
 * block-to-block step pays for the two block lookups.
 *
 * <p>MONITOR with {@code ignoreCancelled = true}: this only observes, and a cancelled move is a move
 * that did not happen, so the destination must not be recorded as somewhere the player stood.
 */
public final class SafeGroundListener implements Listener {

    private final SafeGroundTracker tracker;

    public SafeGroundListener(SafeGroundTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) return;
        tracker.observe(event.getPlayer(), event.getTo());
    }

    /**
     * Seeds a spot on join, so a player who logs in on a ledge and falls before taking a step still
     * has somewhere better than the world spawn to return to.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        tracker.observe(player, player.getLocation());
    }

    /**
     * Teleports are separate from moves: {@code PlayerTeleportEvent} extends {@code PlayerMoveEvent}
     * but has its own handler list, so {@link #onMove} never sees one. Without this, a player who
     * teleports onto an island and stands still would still have their PREVIOUS spot on record.
     *
     * <p>The destination only counts if it is safe ground, so an ender pearl thrown into the air is
     * not recorded.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        tracker.observe(event.getPlayer(), event.getTo());
    }

    /** Same reasoning as {@link #onJoin}: a respawn point is where the player starts over. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        tracker.observe(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracker.forget(event.getPlayer().getUniqueId());
    }
}
