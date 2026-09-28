package me.juancayc.polaroidtotems.listeners;

import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.totem.TotemService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Spends a keep-inventory totem on a death: the player dies, but respawns with everything they
 * carried, and exactly one unit of that totem is gone.
 *
 * <h2>Why a death event and not a resurrect one</h2>
 *
 * <p>This mode does not prevent the death, so there is nothing to intercept before it. {@link
 * PlayerDeathEvent} is the one place the server asks "what does this player lose?", and it answers
 * with two independent knobs this listener turns together: {@code setKeepInventory(true)} leaves the
 * inventory in place, and {@code getDrops().clear()} stops the items being dropped as well. The
 * second one is NOT implied by the first — the event's own javadoc says so — and forgetting it
 * duplicates every item the player carried: one copy kept, one copy on the floor.
 *
 * <h2>Priority: LOW, with ignoreCancelled</h2>
 *
 * <p>The decision has to land BEFORE anything that consumes the drops list. Graves, death chests
 * and "corpse" plugins typically listen at NORMAL or later and move {@code getDrops()} into their
 * own container; a grave built from the drops of a player who then keeps their inventory is a
 * duplication exploit. Running at LOW means those plugins see an empty drops list and {@code
 * getKeepInventory() == true} — which they already handle, because that is exactly what the
 * {@code keepInventory} gamerule looks like to them.
 *
 * <p>LOWEST is left free on purpose. A plugin that makes its OWN keep-inventory decision (per-world
 * keep-inventory, a paid perk) is most naturally written there, and this listener respects it: if
 * the inventory is already being kept by the time we run, nothing is consumed. {@code
 * ignoreCancelled = true} because Paper lets the death itself be cancelled, and a player who is not
 * dying has nothing to keep.
 *
 * <p>The known blind spot is the reverse ordering: a plugin that cancels the death, or flips {@code
 * keepInventory} back to false, at a LATER priority. The first costs the player one totem for a death
 * that did not happen; the second loses the inventory, since the drops were already cleared. Both
 * require another plugin to override a decision this one made openly, and neither is detectable from
 * here without running at MONITOR — where changing the outcome is not allowed.
 *
 * <h2>Threading</h2>
 *
 * <p>Nothing is scheduled. The event fires on the thread that owns the dying player (the region
 * thread under Folia), and every call below — inventory writes, the memory-only cooldown, a chat
 * line — is safe to make right there.
 */
public final class KeepInventoryListener implements Listener {

    private final TotemService totems;

    public KeepInventoryListener(TotemService totems) {
        this.totems = totems;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        // One field read on a server that configured no keeper at all, which is every server until
        // an owner opts in. This fires on every player death.
        if (!totems.registry().hasKeepInventoryTypes()) return;

        // The gamerule, or a plugin that already decided to keep this inventory. The player loses
        // nothing either way, so spending a totem on it would be taking one for nothing.
        if (event.getKeepInventory()) return;

        Player player = event.getEntity();

        TotemService.SearchResult result = totems.searchKeepInventoryTotem(
                player, System.currentTimeMillis(), ResurrectListener.lethalCause(player));
        if (!result.found()) {
            // At most one line, and only when a cooldown is really the reason — the same anti-spam
            // contract as the resurrect path. It names the keeper, not a resurrect totem, so it never
            // repeats what that path may have just said about a different type.
            totems.notifyBlockedByCooldown(player, result);
            return;
        }

        TotemDefinition definition = result.totem().definition();

        event.setKeepInventory(true);
        // CRITICAL — see the class javadoc. Without this, every item is both kept and dropped.
        event.getDrops().clear();

        if (definition.keepExperience()) {
            // Same pairing as the inventory, for the same reason: keepLevel keeps the experience,
            // but "doesn't prevent the EXP from dropping" (its javadoc), so the orbs must go too.
            // Left alone when the type does not ask for it — XP then drops exactly as in vanilla.
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }

        totems.applyKeepInventory(player, result.totem());
    }
}
