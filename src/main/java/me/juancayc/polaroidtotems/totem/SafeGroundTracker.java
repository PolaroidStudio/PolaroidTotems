package me.juancayc.polaroidtotems.totem;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per online player, the last place they were standing on solid ground.
 *
 * <p>This is what {@code rescue.return-to-safe-ground} teleports a saved player back to. The whole
 * point of that key is a death the resurrection alone does not escape — falling into the End void,
 * say — so the plugin has to know where the player was BEFORE they started falling, and the only
 * way to know that is to have been watching.
 *
 * <h2>Memory only, deliberately</h2>
 *
 * <p>A safe spot is a fact about the current session, not something worth a database row: after a
 * restart the player is somewhere else entirely, and the first step they take records a fresh one.
 * Entries are dropped on quit, so the map never holds more than one location per online player.
 *
 * <h2>Threading</h2>
 *
 * <p>A {@link ConcurrentHashMap}, because under Folia move events for different players fire on
 * different region threads at the same time. Each entry is only ever written by the thread that owns
 * that player, so there is no read-modify-write to worry about — just concurrent access to the map.
 *
 * <p>Every location is stored and handed out as a CLONE. {@link Location} is mutable, and a caller
 * that adjusted the returned one (centering it, overwriting yaw) would otherwise be rewriting the
 * recorded spot for the next rescue too.
 */
public final class SafeGroundTracker {

    /**
     * How far below the feet to look for the supporting block.
     *
     * <p>Not zero: a player standing on a full block has feet at exactly {@code y = 65.0}, and the
     * block AT 65 is the air they stand in. Not a full block either: a player on a bottom slab has
     * feet at {@code 64.5}, and one block down would test the block under the slab rather than the
     * slab itself. A hair below the feet lands on the supporting block in both cases.
     */
    private static final double SUPPORT_PROBE = 0.01;

    private final Map<UUID, Location> lastSafe = new ConcurrentHashMap<>();

    /**
     * Records this location for the player if it is safe ground.
     *
     * <p>Cheap enough for a move handler: two block lookups in a chunk the player is already
     * standing in, and the caller only gets here when the block position actually changed.
     *
     * @param player the player who moved
     * @param to     where they are about to be. Taken separately from {@code player.getLocation()}
     *               because on a move event the player has not arrived there yet
     */
    public void observe(Player player, @Nullable Location to) {
        if (to == null) return;
        // Folia: a teleport or respawn destination can sit in a region this thread does not own, and
        // reading its blocks from here throws. Such a spot is simply not recorded now — the player's
        // first step after arriving records it from the right thread. On plain Paper every location
        // is owned by the main thread, so this never rejects anything there.
        if (!Bukkit.isOwnedByCurrentRegion(to)) return;
        if (isSafeGround(player, to)) {
            lastSafe.put(player.getUniqueId(), to.clone());
        }
    }

    /**
     * Whether a player at this location is standing on something solid, on their own feet.
     *
     * <p>"On their own feet" rules out flying (creative flight, {@code /fly}) and elytra gliding: a
     * player skimming over a block is not standing on it, and recording that spot would teleport a
     * later rescue into mid-air. Liquid is ruled out because a player treading water above a solid
     * floor is not somewhere worth returning to — and lava is exactly what they may be dying of.
     *
     * <p>Vehicles are ignored on purpose. A player riding a boat or a horse over solid ground is
     * still over solid ground, and the check below answers that question on its own.
     */
    public static boolean isSafeGround(Player player, Location location) {
        if (player.isFlying() || player.isGliding()) return false;

        World world = location.getWorld();
        if (world == null) return false;

        Block feet = world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (feet.isLiquid()) return false;

        Block support = world.getBlockAt(location.getBlockX(),
                (int) Math.floor(location.getY() - SUPPORT_PROBE), location.getBlockZ());
        return support.getType().isSolid();
    }

    /**
     * The last safe spot recorded for this player, as a fresh clone, or null when none is known.
     */
    public @Nullable Location lastSafeLocation(UUID playerId) {
        Location recorded = lastSafe.get(playerId);
        return recorded == null ? null : recorded.clone();
    }

    /**
     * Where a rescued player should be sent: the recorded safe spot, or the world spawn.
     *
     * <p>The recorded spot is centred on its block so the player lands in the middle of it rather
     * than on the edge they happened to be walking off, and it keeps the player's CURRENT yaw and
     * pitch so the camera does not snap to wherever they were facing seconds ago.
     *
     * <p>Falls back to the spawn of the world the player is in now when:
     *
     * <ul>
     *   <li>nothing was recorded — the player joined and fell before taking a single grounded step;
     *       or</li>
     *   <li>the recorded spot is in a DIFFERENT world — they entered the End and fell before touching
     *       the ground there. Sending them back through a dimension they just left would be far more
     *       surprising than landing at the spawn of the one they are in.</li>
     * </ul>
     *
     * <p>{@link World#getSpawnLocation()} is whatever the server has configured for that world. For
     * the End that is normally the spawn platform area, but a server owner who moved it gets their
     * own choice honoured.
     */
    public Location rescueTarget(Player player) {
        Location current = player.getLocation();
        World world = current.getWorld();

        Location recorded = lastSafe.get(player.getUniqueId());
        Location target;
        if (recorded != null && world != null && world.equals(recorded.getWorld())) {
            target = new Location(world,
                    recorded.getBlockX() + 0.5, recorded.getY(), recorded.getBlockZ() + 0.5);
        } else {
            target = world != null ? world.getSpawnLocation() : current.clone();
        }

        target.setYaw(current.getYaw());
        target.setPitch(current.getPitch());
        return target;
    }

    /** Forgets a player. Called on quit, so the map only ever tracks online players. */
    public void forget(UUID playerId) {
        lastSafe.remove(playerId);
    }
}
