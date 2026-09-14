package me.juancayc.polaroidtotems.totem;

import me.juancayc.polaroidtotems.config.ConfigManager;
import me.juancayc.polaroidtotems.config.TotemsConfig;
import me.juancayc.polaroidtotems.domain.MythicSkillSpec;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.domain.TotemEffectSpec;
import me.juancayc.polaroidtotems.item.TotemStamper;
import me.juancayc.polaroidtotems.messaging.MessageService;
import me.juancayc.polaroidtotems.skill.MythicSkillHook;
import me.juancayc.polaroidtotems.util.DurationFormat;
import me.juancayc.polaroidtotems.util.SoundService;
import org.bukkit.EntityEffect;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the plugin does when a player is about to die with a totem somewhere on them.
 *
 * <p>The listener owns the event; this class owns the decision and the consequences, so the rules
 * below live in one readable place rather than inside an event handler.
 */
public final class TotemService {

    private final Plugin plugin;
    private final ConfigManager config;
    private final TotemsConfig totems;
    private final TotemStamper stamper;
    private final MessageService messages;
    private final SoundService sounds;
    private final MythicSkillHook skills;
    private final CooldownService cooldowns;

    public TotemService(Plugin plugin, ConfigManager config, TotemsConfig totems,
                        TotemStamper stamper, MessageService messages, SoundService sounds,
                        MythicSkillHook skills, CooldownService cooldowns) {
        this.plugin = plugin;
        this.config = config;
        this.totems = totems;
        this.stamper = stamper;
        this.messages = messages;
        this.sounds = sounds;
        this.skills = skills;
        this.cooldowns = cooldowns;
    }

    /** One totem found somewhere in an inventory, with the slot it was found in. */
    public record FoundTotem(int slot, ItemStack stack, TotemDefinition definition) {}

    /**
     * The outcome of one inventory search: the totem to use, or why there was none.
     *
     * <p>Richer than a nullable {@link FoundTotem} for exactly one reason — the cooldown message.
     * A player dying is a noisy moment, and a player carrying four totems of three types would
     * otherwise receive one refusal line per scanned stack. So the search does not message anybody:
     * it REPORTS the nearest cooldown it had to skip, and the caller decides whether that is worth
     * saying at all. It never is when a later totem saved the player.
     *
     * @param totem            the totem that will be used, or null when none was usable
     * @param blockedDefinition the type whose cooldown came closest to expiring among those skipped,
     *                         or null when no totem was skipped for a cooldown
     * @param blockedRemainingMillis how long that type still has to wait
     */
    public record SearchResult(@Nullable FoundTotem totem,
                               @Nullable TotemDefinition blockedDefinition,
                               long blockedRemainingMillis) {

        private static final SearchResult NOTHING = new SearchResult(null, null, 0L);

        /** No totem, and nothing worth telling the player about. */
        public static SearchResult nothing() {
            return NOTHING;
        }

        /** True when a totem was found and the player is about to be saved. */
        public boolean found() {
            return totem != null;
        }

        /** True when nothing was usable AND at least one totem was withheld by a cooldown. */
        public boolean blockedByCooldown() {
            return totem == null && blockedDefinition != null;
        }
    }

    /**
     * Finds the first usable totem anywhere in the player's inventory.
     *
     * <p>Search order is deliberate and stable: main hand, off hand, then the storage slots in
     * index order, then armour when {@code activation.include-armor-slots} is on. A player who
     * wants a specific totem used first puts it in their hand, exactly as in vanilla.
     *
     * <p>Kept as the narrow answer for callers that only want the totem. Everything that needs to
     * know WHY there was none goes through {@link #searchUsableTotem}.
     *
     * @return null when nothing usable was found — no totem at all, only totems whose type requires
     *         a permission the player lacks, only types blocked in this world, or only types the
     *         player is still on cooldown for
     */
    public @Nullable FoundTotem findUsableTotem(Player player) {
        return searchUsableTotem(player, System.currentTimeMillis()).totem();
    }

    /**
     * The full search, including why nothing was usable.
     *
     * @param now one clock reading for the whole search, so every totem in the inventory is judged
     *            against the same instant. Taking {@code System.currentTimeMillis()} per slot would
     *            let a long inventory scan expire a cooldown halfway through its own decision
     */
    public SearchResult searchUsableTotem(Player player, long now) {
        // The GLOBAL world blacklist short-circuits the entire search rather than being re-asked per
        // slot. In a blacklisted world the answer is the same for every totem in every slot, so
        // walking 41 slots to reach it would be 41 hash lookups, a registry resolve and a PDC read
        // each, on the main thread, on every lethal hit taken in that world. One lookup answers it.
        //
        // It is also the honest reading of the key: `worlds.blacklist` says no totem works here,
        // which is a statement about the world, not about any totem.
        if (config.worldBlacklist().isWorldBlocked(player.getWorld().getName())) {
            // Deliberately reported as "nothing", not as a cooldown: the player is not waiting for
            // anything and telling them a duration would be a lie.
            return SearchResult.nothing();
        }

        PlayerInventory inventory = player.getInventory();
        Search search = new Search(player, now);

        FoundTotem hand = search.candidate(inventory.getHeldItemSlot(),
                inventory.getItem(inventory.getHeldItemSlot()));
        if (hand != null) return search.using(hand);

        // 40 is the off-hand slot index in a PlayerInventory.
        FoundTotem offHand = search.candidate(40, inventory.getItemInOffHand());
        if (offHand != null) return search.using(offHand);

        // Storage = the 36 main slots (hotbar + the three rows), excluding armour and off hand.
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            FoundTotem found = search.candidate(slot, storage[slot]);
            if (found != null) return search.using(found);
        }

        if (config.activateFromArmor()) {
            ItemStack[] armor = inventory.getArmorContents();
            for (int index = 0; index < armor.length; index++) {
                // Armour slots start at 36 in a PlayerInventory's flat index space.
                FoundTotem found = search.candidate(36 + index, armor[index]);
                if (found != null) return search.using(found);
            }
        }
        return search.exhausted();
    }

    /**
     * One inventory walk, carrying the per-search state {@link #candidate} needs.
     *
     * <p>A small object rather than three parameters threaded through every call, because the
     * interesting part — remembering the closest cooldown across slots — is state, and state passed
     * as arguments is state that eventually gets passed wrong.
     *
     * <p>Allocated once per resurrection, which is a rare event by definition. This is not a
     * per-tick path.
     */
    private final class Search {

        private final Player player;
        private final long now;

        private @Nullable TotemDefinition nearestBlocked;
        private long nearestRemainingMillis = Long.MAX_VALUE;

        private Search(Player player, long now) {
            this.player = player;
            this.now = now;
        }

        /**
         * Decides whether one stack is a totem this player may be saved by, right now, here.
         *
         * <p>Every rejection returns null so the caller's loop falls through to the NEXT totem. That
         * is the correct behaviour for all three rules: a player carrying an on-cooldown {@code
         * ember} and a ready {@code guardian} is saved by the guardian, not killed by the ember.
         */
        private @Nullable FoundTotem candidate(int slot, @Nullable ItemStack stack) {
            if (!isTotemMaterial(stack)) return null;

            // An untagged totem is the reserved vanilla type by definition, so a server that never
            // stamped anything still gets the configured vanilla behaviour.
            TotemDefinition definition = totems.registry().resolveOrVanilla(stamper.readType(stack));

            String permission = definition.permission();
            if (permission != null && !player.hasPermission(permission)) return null;

            // Per-totem world rule. The global one was answered before the walk began, so this is
            // only ever the `per-totem-blacklist` half.
            if (config.worldBlacklist().isBlocked(player.getWorld().getName(), definition.id())) {
                return null;
            }

            long remaining = cooldowns.remainingMillis(player, definition, now);
            if (remaining > 0L) {
                // Remembered, not announced. Whether the player hears about this at all depends on
                // whether a LATER slot saves them, which is not known yet.
                if (remaining < nearestRemainingMillis) {
                    nearestRemainingMillis = remaining;
                    nearestBlocked = definition;
                }
                return null;
            }

            return new FoundTotem(slot, stack, definition);
        }

        /** A totem was found: nothing skipped along the way is worth mentioning. */
        private SearchResult using(FoundTotem found) {
            return new SearchResult(found, null, 0L);
        }

        /**
         * The walk finished with nothing usable.
         *
         * <p>Reports the cooldown closest to expiring, of all the ones skipped. That is the most
         * useful single number: it is when the player can next expect to be saved, and it is the
         * only one that stays true no matter which of their totems they were counting on.
         */
        private SearchResult exhausted() {
            return nearestBlocked == null
                    ? SearchResult.nothing()
                    : new SearchResult(null, nearestBlocked, nearestRemainingMillis);
        }
    }

    /**
     * Tells a player, exactly once, that a cooldown is why no totem saved them.
     *
     * <p>Called from the listener after the search came back empty, and only then — which is the
     * entire anti-spam design. The search itself sends nothing, so a player carrying six totems gets
     * one line rather than six, and a player whose seventh totem worked gets none at all.
     */
    public void notifyBlockedByCooldown(Player player, SearchResult result) {
        if (!result.blockedByCooldown()) return;

        messages.sendPrefixed(player, "totem.cooldown",
                "%totem%", messages.escape(result.blockedDefinition().id()),
                "%time%", DurationFormat.remaining(result.blockedRemainingMillis()));
    }

    /**
     * Cheap material test used as the early-exit of every hot path.
     *
     * <p>A Nexo-backed totem is still a {@code TOTEM_OF_UNDYING} underneath unless the server owner
     * points {@code item:} at a different material — in which case that type is only reachable from
     * a hand, like vanilla. Widening this test to "any item with our PDC tag" would mean reading
     * item meta for every slot of every inventory click, which is the one thing the normalization
     * listener must not do.
     */
    public static boolean isTotemMaterial(@Nullable ItemStack stack) {
        return stack != null && stack.getType() == Material.TOTEM_OF_UNDYING;
    }

    /**
     * Applies every consequence of a successful resurrection: consuming the totem, the animation,
     * the sound, the health top-up and the custom effects.
     *
     * <p>The caller has already un-cancelled the event.
     *
     * @param consumeManually true when the plugin must remove the item itself. On the
     *                        inventory-wide path vanilla consumes NOTHING, because it never saw a
     *                        totem in a hand; on the hand-held path vanilla has already taken one.
     */
    public void applyResurrection(Player player, FoundTotem found, boolean consumeManually) {
        TotemDefinition definition = found.definition();

        if (consumeManually && definition.consume()) {
            consumeOne(player, found);
        }

        // Started here rather than in the search, because the search only decides what COULD be
        // used: on the hand-held path vanilla may still have its own reasons, and a cooldown burned
        // for a resurrection that did not happen would be a totem stolen from the player.
        //
        // Memory-only and immediate — see CooldownService. This adds no I/O to the death path.
        cooldowns.startCooldown(player, definition, System.currentTimeMillis());

        if (config.playAnimation()) {
            // The resurrect animation does NOT play by itself on the forced path — vanilla only
            // triggers it inside the branch that consumed a hand-held totem.
            //
            // PROTECTED_FROM_DEATH, not TOTEM_RESURRECT: the latter has been deprecated for removal
            // since 1.21.2 in favour of this one. Both carry the same wire id (35), so the client
            // sees exactly the same animation — and this constant's contract is the better fit
            // anyway: it shows the item that carries the DEATH_PROTECTION component, falling back
            // to a totem when it cannot find one.
            player.playEffect(EntityEffect.PROTECTED_FROM_DEATH);
        }

        sounds.playResurrect(player);

        if (definition.healToFull()) {
            // Vanilla leaves the player on 1 HP. A type may instead top them up to their real
            // maximum, which is an attribute and not a constant 20.
            var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                player.setHealth(maxHealth.getValue());
            }
        }

        if (config.announceToPlayer()) {
            messages.sendPrefixed(player, "totem.saved",
                    "%totem%", messages.escape(definition.id()));
        }

        scheduleAfterEffects(player, definition);
    }

    /**
     * Applies a type's custom effects and casts its MythicMobs skills, one tick later.
     *
     * <p>This delay is not politeness, it is correctness. Vanilla applies its own Regeneration II,
     * Absorption II and Fire Resistance as part of the resurrect branch, which runs AFTER the plugin
     * un-cancels the event. A custom effect applied in the same tick is simply overwritten by those.
     *
     * <p>Scheduled on the entity scheduler rather than Bukkit's: every BukkitScheduler call throws
     * UnsupportedOperationException on Folia, and this work belongs to one specific entity.
     *
     * <p>Named "after-effects" rather than "effects" because it now owns two unrelated lists. The
     * guard checks BOTH: an earlier version returned on an empty {@code effects()} alone, which
     * would silently swallow every skill on a totem that grants no potion effects.
     */
    private void scheduleAfterEffects(Player player, TotemDefinition definition) {
        if (definition.effects().isEmpty() && definition.skills().isEmpty()) return;

        long delay = config.effectDelayTicks();
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline() || player.isDead()) return;
            for (TotemEffectSpec spec : definition.effects()) {
                player.addPotionEffect(spec.toPotionEffect());
            }

            // Skills share the one delayed task rather than getting their own, for two reasons that
            // happen to agree. First, the same ordering argument as the potion effects above:
            // vanilla's resurrect branch runs after the un-cancel, so a skill fired in the original
            // tick would be reading (and buffing) a player vanilla is still rewriting. Second,
            // Mythic's castSkill does no scheduling of its own — it executes on the calling thread —
            // so it MUST be invoked on the thread owning this entity, which under Folia is exactly
            // this entity-scheduler task and nothing else.
            //
            // Ordering within the tick is deliberate too: potion effects first, so a skill that
            // inspects or overwrites the player's effects sees the totem's own contribution already
            // applied rather than racing it.
            for (MythicSkillSpec spec : definition.skills()) {
                skills.cast(player, spec, definition);
            }
        }, null, delay);
    }

    /**
     * Removes exactly one totem from the slot it was found in.
     *
     * <p>Only ever called on the inventory-wide path. With {@code getHand() == null}, vanilla
     * consumed nothing, so leaving this out would hand the player an infinite totem.
     */
    private void consumeOne(Player player, FoundTotem found) {
        Inventory inventory = player.getInventory();
        ItemStack stack = found.stack();

        if (stack.getAmount() > 1) {
            stack.setAmount(stack.getAmount() - 1);
        } else {
            inventory.setItem(found.slot(), null);
        }
    }

    public TotemRegistry registry() {
        return totems.registry();
    }
}
