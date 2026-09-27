package me.juancayc.polaroidtotems.listeners;

import me.juancayc.polaroidtotems.config.ConfigManager;
import me.juancayc.polaroidtotems.totem.TotemService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.PlayerInventory;

/**
 * Lets a totem that names {@code VOID} in its {@code conditions.causes} save a player from the void.
 *
 * <h2>Why this cannot go through {@link ResurrectListener}</h2>
 *
 * <p>Vanilla never lets ANY totem prevent a void death, and it does not even ask. In 1.21.11 the
 * {@code out_of_world} damage type is in {@code #minecraft:bypasses_invulnerability}, and {@code
 * LivingEntity#checkTotemDeathProtection} returns false for that tag on its very first line — before
 * it looks at the hands and before it raises {@code EntityResurrectEvent}. So the resurrect event
 * never fires for a void death, and no amount of un-cancelling can help. (Verified against the
 * Paper 1.21.11 server sources and the vanilla tag file.) The only point left to intervene is the
 * damage event of the hit that would kill them.
 *
 * <h2>Strictly opt-in</h2>
 *
 * <p>Only a type that LISTS {@code VOID} qualifies. An unrestricted type — every totem that existed
 * before {@code conditions:} did — is ignored here, even though it "matches any cause" everywhere
 * else. Otherwise installing this version would quietly turn every ordinary totem on the server into
 * a void rescue, which is a gameplay change nobody configured.
 *
 * <h2>Priority</h2>
 *
 * <p>HIGH with {@code ignoreCancelled = true}, mirroring {@link ResurrectListener}: late enough that
 * a protection plugin that already cancelled the hit wins (the player is not dying, so there is
 * nothing to save), early enough that a MONITOR listener still sees the final outcome.
 */
public final class VoidRescueListener implements Listener {

    /** The off-hand slot in a {@code PlayerInventory}'s flat index space. */
    private static final int OFF_HAND_SLOT = 40;

    private final ConfigManager config;
    private final TotemService totems;

    public VoidRescueListener(ConfigManager config, TotemService totems) {
        this.config = config;
        this.totems = totems;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVoidDamage(EntityDamageEvent event) {
        // Cause first: it is a field read, and this event fires for every hit on every entity.
        if (event.getCause() != DamageCause.VOID) return;
        if (!(event.getEntity() instanceof Player player)) return;

        // Only the LETHAL hit. Vanilla applies the final damage straight to health (absorption was
        // already taken out as its own modifier), and death is health reaching zero — so this is
        // the exact test the server is about to make.
        if (player.getHealth() - event.getFinalDamage() > 0) return;

        TotemService.SearchResult result = totems.searchUsableTotem(
                player, System.currentTimeMillis(), DamageCause.VOID, true);
        if (!result.found()) {
            // Deliberately silent, cooldown or not. A void death is already the end of the story
            // for a player without a VOID totem, and one that is on cooldown reads the same way it
            // would on the inventory path — except this runs every tick the player is below the
            // world, so a message here would be a message per tick.
            return;
        }

        // A hand-held VOID totem is honoured unconditionally, like any hand-held totem in vanilla.
        // One found elsewhere is the inventory-wide feature, and answers to the same two gates the
        // ordinary inventory path does.
        if (!isHand(player.getInventory(), result.totem().slot())) {
            if (!config.activateFromInventory()) return;
            String permission = config.activationPermission();
            if (permission != null && !player.hasPermission(permission)) return;
        }

        event.setCancelled(true);
        totems.resurrectWithoutVanilla(player, result.totem(), event.getDamage());
    }

    private static boolean isHand(PlayerInventory inventory, int slot) {
        return slot == inventory.getHeldItemSlot() || slot == OFF_HAND_SLOT;
    }
}
