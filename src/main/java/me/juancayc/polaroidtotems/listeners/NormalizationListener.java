package me.juancayc.polaroidtotems.listeners;

import me.juancayc.polaroidtotems.config.ConfigManager;
import me.juancayc.polaroidtotems.config.TotemsConfig;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.item.TotemStamper;
import me.juancayc.polaroidtotems.totem.TotemService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Stamps untagged Totems of Undying as the reserved {@value TotemDefinition#VANILLA_ID} type.
 *
 * <h2>Why this class has to exist</h2>
 *
 * <p>Minecraft decides whether two stacks merge by comparing their components. A totem carrying this
 * plugin's PDC tag and stack-size component is therefore a different item from a plain one and the
 * two will never stack together. Without normalization a player who mines, trades and loots totems
 * ends up with a shelf of one-item stacks that refuse to combine — which looks exactly like a bug
 * even though it is the mechanism doing its job.
 *
 * <p>So: any totem with no tag becomes type {@code vanilla}, and from then on everything the player
 * holds shares one identity and stacks to whatever {@code vanilla.stack-size} says.
 *
 * <h2>Keeping it cheap</h2>
 *
 * <p>These are hot events — a click event fires on every single inventory interaction on the
 * server. Every handler below early-exits on the <em>material</em> first
 * ({@link TotemService#isTotemMaterial}), which is a field comparison, before anything reads item
 * meta. The join and open scans walk storage contents only, never armour or the cursor, and skip a
 * stack that is already tagged so an idempotent stamp never churns the inventory.
 */
public final class NormalizationListener implements Listener {

    private final ConfigManager config;
    private final TotemsConfig totems;
    private final TotemStamper stamper;

    public NormalizationListener(ConfigManager config, TotemsConfig totems, TotemStamper stamper) {
        this.config = config;
        this.totems = totems;
        this.stamper = stamper;
    }

    /** A totem picked up off the ground, from a mob drop or from a hopper-fed chest. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!config.normalizeEnabled() || !config.normalizeOnPickup()) return;
        if (!(event.getEntity() instanceof Player)) return;

        ItemStack stack = event.getItem().getItemStack();
        if (!TotemService.isTotemMaterial(stack) || stamper.isTagged(stack)) return;

        stamper.stamp(stack, totems.registry().vanilla());
        // Written back rather than mutated in place: Item#getItemStack is documented only as
        // "gets the item stack", with no promise that the returned object is a live reference.
        // setItemStack is the documented way to make a change stick.
        event.getItem().setItemStack(stack);
    }

    /** Everything already in the inventory when the plugin was installed or the player logged in. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.normalizeEnabled() || !config.normalizeOnJoin()) return;
        scan(event.getPlayer().getInventory());
    }

    /** Cheap catch-all for totems moved in from a chest, a shulker box or a shop GUI. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!config.normalizeEnabled() || !config.normalizeOnInventoryClick()) return;

        // Only the two stacks this click could possibly have touched, never the whole inventory:
        // a full scan on every click is exactly the cost this listener must not pay.
        //
        // Each is written back through the event's own setter for the same reason the scan writes
        // back by index: neither getter promises a live reference.
        ItemStack clicked = event.getCurrentItem();
        if (stampIfNeeded(clicked)) {
            event.setCurrentItem(clicked);
        }

        ItemStack cursor = event.getCursor();
        if (stampIfNeeded(cursor)) {
            event.getView().setCursor(cursor);
        }
    }

    /** Catches a chest or ender chest full of totems the moment it is opened. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!config.normalizeEnabled() || !config.normalizeOnInventoryOpen()) return;
        scan(event.getInventory());
    }

    /**
     * Walks an inventory's storage slots and writes every stamped stack back by index.
     *
     * <p>The write-back is deliberate. {@code getStorageContents()} is documented to "return the
     * contents" and says nothing about whether the elements are live references or copies — so
     * mutating one in place and hoping it lands is relying on an implementation detail rather than
     * on the contract. {@code setItem} is the contract.
     *
     * <p>It stays cheap because the slot is only written when a stamp actually happened: an
     * inventory with no untagged totems performs zero writes.
     */
    private void scan(Inventory inventory) {
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stampIfNeeded(stack)) {
                inventory.setItem(slot, stack);
            }
        }
    }

    /**
     * Stamps a stack if it is an untagged totem.
     *
     * <p>An already-tagged totem is skipped: the stamp is idempotent, but the item-meta round trip
     * is not free on a path this hot.
     *
     * @return true when the stack was actually stamped, so the caller knows whether a write-back
     *         is needed at all
     */
    private boolean stampIfNeeded(ItemStack stack) {
        if (!TotemService.isTotemMaterial(stack) || stamper.isTagged(stack)) return false;
        stamper.stamp(stack, totems.registry().vanilla());
        return true;
    }
}
