package me.juancayc.polaroidtotems.listeners;

import me.juancayc.polaroidtotems.config.ConfigManager;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.totem.TotemService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

/**
 * Turns {@link EntityResurrectEvent} into a custom-totem resurrection.
 *
 * <h2>How this event actually behaves</h2>
 *
 * <p>Four facts drive every line below, and getting any of them wrong produces a plugin that either
 * does nothing or throws on every death:
 *
 * <ol>
 *   <li><strong>It fires on every lethal hit, always.</strong> Not only when a totem is present. An
 *       entity about to die with empty hands still raises it — pre-cancelled.</li>
 *   <li><strong>{@code getHand()} returns null when the event is pre-cancelled.</strong> That is
 *       the normal case, not an edge case, so it must be null-checked before anything else touches
 *       it.</li>
 *   <li><strong>{@code ignoreCancelled} must stay false</strong> (the default, spelled out here for
 *       the reader). Setting it true would skip exactly the pre-cancelled events this plugin
 *       exists to handle.</li>
 *   <li><strong>{@code setCancelled(false)} forces the resurrection.</strong> When {@code getHand()}
 *       was null, vanilla consumes nothing, plays no animation and applies no effects for a totem it
 *       never saw — so the plugin does all three itself.</li>
 * </ol>
 *
 * <p>Priority is HIGH rather than MONITOR because the decision is a real one: a later listener may
 * still want to veto it, and a MONITOR handler must not change outcomes.
 *
 * <h2>Keep-inventory totems never resurrect</h2>
 *
 * <p>The search skips them, so on the pre-cancelled path they are simply invisible. The one place
 * they leak in is a HAND: a keep-inventory type backed by a Totem of Undying still carries vanilla's
 * {@code death_protection} component, so vanilla picks it up and starts resurrecting with it. See
 * {@link #handleKeeperInHand} for how that is turned back into what the owner configured.
 */
public final class ResurrectListener implements Listener {

    private final ConfigManager config;
    private final TotemService totems;

    public ResurrectListener(ConfigManager config, TotemService totems) {
        this.config = config;
        this.totems = totems;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        // Fact 2: null on the pre-cancelled path, which is most of them. Reading any method on it
        // without this check is an NPE on literally every death on the server.
        boolean handHeld = event.getHand() != null;

        // ONE clock reading for the whole decision. Threaded through the search so every totem in
        // the inventory is judged against the same instant rather than against a clock that advances
        // mid-scan.
        long now = System.currentTimeMillis();

        // What killed them, for each type's `conditions.causes`. Read once, before either branch.
        DamageCause cause = lethalCause(player);

        if (handHeld) {
            // Vanilla is already resurrecting the player with a totem it found in a hand. It will
            // consume that totem and play the animation itself, so the plugin only adds this type's
            // custom effects on top. Nothing is un-cancelled here because nothing was cancelled.
            if (event.isCancelled()) return;

            ItemStack handStack = player.getInventory().getItem(event.getHand());
            TotemDefinition handType = totems.typeOf(handStack);
            if (handType != null && handType.keepsInventory()) {
                handleKeeperInHand(event, player, handStack, now, cause);
                return;
            }

            //
            // Conditions are handled exactly like cooldowns here, through the same search. If the
            // hand-held type's conditions do not match this death, vanilla still saves the player
            // (and still consumes that totem — the plugin cannot stop it without killing them), the
            // search skips that type, and custom behaviour comes from the next usable totem it finds
            // or from nobody. So a `voidwalker` held while dying in lava is spent as a plain totem:
            // no slow falling, no rescue teleport, no cooldown started for it.
            TotemService.SearchResult result = totems.searchUsableTotem(player, now, cause);
            if (!result.found()) {
                // The hand-held branch deliberately says NOTHING about a cooldown. Vanilla is
                // resurrecting this player with or without us — they are not being refused, so a
                // "wait 2m 30s" line would contradict the totem they just watched save them.
                return;
            }
            totems.applyResurrection(player, result.totem(), false);
            return;
        }

        // From here on the event is pre-cancelled: vanilla found no hand-held totem and the player
        // is about to die.
        if (!config.activateFromInventory()) return;

        String permission = config.activationPermission();
        if (permission != null && !player.hasPermission(permission)) return;

        TotemService.SearchResult result = totems.searchUsableTotem(player, now, cause);
        if (!result.found()) {
            // THE one place a cooldown refusal is announced, and the reason the search itself sends
            // nothing. A player dying is a noisy moment: this sends at most one line no matter how
            // many totems were scanned, and sends none at all when a later totem saved them (that
            // branch never reaches here) or when nothing was on cooldown in the first place.
            totems.notifyBlockedByCooldown(player, result);
            return;
        }

        // Fact 4: this is what actually saves the player.
        event.setCancelled(false);

        // consumeManually = true: vanilla took nothing, because it never saw a totem.
        totems.applyResurrection(player, result.totem(), true);
    }

    /**
     * Vanilla is resurrecting the player with a keep-inventory totem it found in a hand.
     *
     * <p>That must not happen as-is: a keeper never resurrects anybody, and it would also be spent
     * on the wrong job. What happens instead is exactly what would have happened had the keeper been
     * anywhere else in the inventory:
     *
     * <ol>
     *   <li><strong>A usable RESURRECT totem exists</strong> (subject to the same gates as always: a
     *       hand is honoured unconditionally, anywhere else needs {@code activation.from-inventory}
     *       and the activation permission) — the player is resurrected by THAT totem. Holding a
     *       keeper must never make a player die who would have been saved without it.</li>
     *   <li><strong>None</strong> — the event is cancelled, the player dies, and {@link
     *       KeepInventoryListener} spends the keeper on the death, as it was configured to.</li>
     * </ol>
     *
     * <h2>Why the hand stack is written back in case 1</h2>
     *
     * <p>Paper consumes the hand totem AFTER this event returns, not before, and only if it stays
     * un-cancelled (verified against Paper's 1.21.11 {@code LivingEntity} patch: the vanilla {@code
     * itemInHand.shrink(1)} is moved below {@code callEvent} and runs on the {@code itemInHand}
     * reference it captured before the event). Cancelling and un-cancelling would therefore still
     * shrink the keeper. So the hand slot is replaced with a fresh copy of the same stack: vanilla then
     * shrinks the orphaned original it is still holding, and the player keeps every keeper they had.
     * This works whether {@code getItem} handed back a live mirror or a copy — {@code setItem} always
     * installs a NEW server stack — so it does not depend on either implementation detail. The
     * resurrect totem that actually saved them is then consumed manually, exactly as on the
     * inventory-wide path.
     *
     * <p>Vanilla still applies the keeper's own {@code death_protection} effects (Regeneration II,
     * Absorption II, Fire Resistance for a Totem of Undying) — identical to what the resurrect totem
     * would have granted — and the resurrect type's custom effects are layered on one tick later.
     */
    private void handleKeeperInHand(EntityResurrectEvent event, Player player, ItemStack handStack,
                                    long now, @Nullable DamageCause cause) {
        PlayerInventory inventory = player.getInventory();
        boolean inventoryAllowed = inventoryActivationAllowed(player);

        TotemService.SearchResult result = totems.searchUsableTotem(player, now, cause);
        TotemService.FoundTotem found = result.totem();

        if (found != null && (inventoryAllowed || TotemService.isHandSlot(inventory, found.slot()))) {
            inventory.setItem(event.getHand(), handStack.clone());
            totems.applyResurrection(player, found, true);
            return;
        }

        // No resurrection: the player dies, and the death listener takes it from here.
        event.setCancelled(true);

        // Same anti-spam rule as the pre-cancelled path, which is what this has just become: one
        // cooldown line, only when a cooldown is really why no resurrect totem saved them, and only
        // when the inventory-wide feature was open to this player in the first place.
        if (inventoryAllowed) {
            totems.notifyBlockedByCooldown(player, result);
        }
    }

    /** The two inventory-wide gates, exactly as the pre-cancelled path applies them. */
    private boolean inventoryActivationAllowed(Player player) {
        if (!config.activateFromInventory()) return false;
        String permission = config.activationPermission();
        return permission == null || player.hasPermission(permission);
    }

    /**
     * The cause of the hit that is killing this player right now.
     *
     * <p>{@link EntityResurrectEvent} carries no damage source of its own — its whole API is {@code
     * getHand()} and cancellation. So the cause is read from {@code getLastDamageCause()}, which is
     * reliable at this exact moment, and the ordering in the server is why (verified against the
     * Paper 1.21.11 sources): {@code LivingEntity#hurtServer} fires the {@code EntityDamageEvent}
     * first, and {@code CraftEventFactory#callEntityDamageEvent} stores it with {@code
     * setLastDamageCause} as soon as it comes back un-cancelled. Only then is the damage applied,
     * and only once health has reached zero does {@code checkTotemDeathProtection} raise the
     * resurrect event — all inside the same call. So the stored event IS the lethal hit, never an
     * older one.
     *
     * <p>Null when the player has never been damaged this session. Types restricted by cause then do
     * not fire, which is the safe reading: a void-only totem must not fire for a death nobody can
     * name. The one known blind spot is a resurrect raised WITHOUT a damage event in front of it
     * (another plugin driving the death directly); the value is then whatever hit came before. No
     * vanilla death takes that route.
     */
    static @Nullable DamageCause lethalCause(org.bukkit.entity.Player player) {
        EntityDamageEvent last = player.getLastDamageCause();
        return last == null ? null : last.getCause();
    }
}
