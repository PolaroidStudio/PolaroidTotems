package me.juancayc.polaroidtotems.listeners;

import me.juancayc.polaroidtotems.config.ConfigManager;
import me.juancayc.polaroidtotems.totem.TotemService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;

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

        if (handHeld) {
            // Vanilla is already resurrecting the player with a totem it found in a hand. It will
            // consume that totem and play the animation itself, so the plugin only adds this type's
            // custom effects on top. Nothing is un-cancelled here because nothing was cancelled.
            if (event.isCancelled()) return;

            TotemService.SearchResult result = totems.searchUsableTotem(player, now);
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

        TotemService.SearchResult result = totems.searchUsableTotem(player, now);
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
}
