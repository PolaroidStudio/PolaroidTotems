package me.juancayc.polaroidtotems.totem;

import me.juancayc.polaroidtotems.config.ConfigManager;
import me.juancayc.polaroidtotems.config.TotemsConfig;
import me.juancayc.polaroidtotems.domain.MythicSkillSpec;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.domain.TotemEffectSpec;
import me.juancayc.polaroidtotems.domain.TotemMode;
import me.juancayc.polaroidtotems.item.TotemStamper;
import me.juancayc.polaroidtotems.messaging.MessageService;
import me.juancayc.polaroidtotems.skill.MythicSkillHook;
import me.juancayc.polaroidtotems.util.DurationFormat;
import me.juancayc.polaroidtotems.util.SoundService;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
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
    private final SafeGroundTracker safeGround;

    public TotemService(Plugin plugin, ConfigManager config, TotemsConfig totems,
                        TotemStamper stamper, MessageService messages, SoundService sounds,
                        MythicSkillHook skills, CooldownService cooldowns,
                        SafeGroundTracker safeGround) {
        this.plugin = plugin;
        this.config = config;
        this.totems = totems;
        this.stamper = stamper;
        this.messages = messages;
        this.sounds = sounds;
        this.skills = skills;
        this.cooldowns = cooldowns;
        this.safeGround = safeGround;
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
     * <p>No damage is involved here, so the cause is unknown: a type restricted by {@code
     * conditions.causes} is never returned, while unrestricted ones are.
     *
     * @return null when nothing usable was found — no totem at all, only totems whose type requires
     *         a permission the player lacks, only types blocked in this world, only types the
     *         player is still on cooldown for, or only types whose conditions do not match
     */
    public @Nullable FoundTotem findUsableTotem(Player player) {
        return searchUsableTotem(player, System.currentTimeMillis(), null).totem();
    }

    /**
     * The full search for an ordinary lethal hit — one vanilla itself would let a totem prevent.
     *
     * @param now   one clock reading for the whole search, so every totem in the inventory is judged
     *              against the same instant. Taking {@code System.currentTimeMillis()} per slot would
     *              let a long inventory scan expire a cooldown halfway through its own decision
     * @param cause what the lethal hit was, or null when it could not be determined. Checked against
     *              each type's {@code conditions}; see {@link
     *              me.juancayc.polaroidtotems.domain.TotemConditions#matches}
     */
    public SearchResult searchUsableTotem(Player player, long now, @Nullable DamageCause cause) {
        return searchUsableTotem(player, now, cause, false);
    }

    /**
     * The full search, including why nothing was usable.
     *
     * @param causeMustBeListed true for a death vanilla never lets a totem prevent (the void). Then
     *                          only a type that LISTS {@code cause} in its conditions qualifies, and
     *                          an unrestricted type — which ordinarily matches every cause — is
     *                          skipped. See {@link
     *                          me.juancayc.polaroidtotems.domain.TotemConditions#listsCause}
     */
    public SearchResult searchUsableTotem(Player player, long now, @Nullable DamageCause cause,
                                          boolean causeMustBeListed) {
        // Resurrect types only: a keep-inventory totem never cancels a death, so it is invisible
        // here — skipped exactly like a stack of dirt, before permission, conditions or cooldown are
        // even asked. It stays in the inventory for the death listener to find.
        //
        // Storage is always walked: the inventory-wide gates (`activation.from-inventory`, the
        // activation permission) are applied by the resurrect listener AFTER the search, because the
        // hand-held branch must be able to look past them. Armour only when the owner opted in.
        return search(player, new Search(player, now, cause, causeMustBeListed,
                player.getWorld().getEnvironment(), TotemMode.RESURRECT), true, config.activateFromArmor());
    }

    /**
     * Finds the keep-inventory totem that should be spent on this death, if any.
     *
     * <p>The same rules as a resurrection, applied to the other mode: the world blacklist (global
     * and per type), the type's permission, its {@code conditions:} and its cooldown. The
     * inventory-wide gates are applied HERE rather than by the caller, with exactly the resurrect
     * path's semantics — a keeper in either hand always counts, one in storage needs {@code
     * activation.from-inventory} plus the activation permission, one in armour additionally needs
     * {@code activation.include-armor-slots}. The death listener has no hand-held branch that must
     * look past them, so filtering while walking is simpler than filtering afterwards.
     *
     * <h2>Why every stack, not just totems</h2>
     *
     * <p>The resurrection search early-exits on {@code TOTEM_OF_UNDYING} because it runs on every
     * lethal hit and vanilla only ever resurrects with that material anyway. A death is rarer than a
     * lethal hit, and a keep-inventory totem has no reason to be a Totem of Undying at all — a Nexo
     * item on {@code PAPER} makes a fine "soul contract". So this walk reads the type tag of every
     * NON-EMPTY stack instead. {@code readType} checks {@code hasItemMeta()} before touching the PDC,
     * so a plain stack of cobblestone costs one boolean. At most 41 of them, once per death.
     *
     * @param now   one clock reading for the whole search, as in {@link #searchUsableTotem}
     * @param cause the cause of the lethal hit, or null when it could not be determined
     */
    public SearchResult searchKeepInventoryTotem(Player player, long now, @Nullable DamageCause cause) {
        String permission = config.activationPermission();
        boolean fromInventory = config.activateFromInventory()
                && (permission == null || player.hasPermission(permission));

        return search(player, new Search(player, now, cause, false,
                        player.getWorld().getEnvironment(), TotemMode.KEEP_INVENTORY),
                fromInventory, fromInventory && config.activateFromArmor());
    }

    /**
     * The one inventory walk both modes share.
     *
     * @param includeStorage whether the 36 main slots are searched at all; the two hands always are
     * @param includeArmor   whether the four armour slots are searched after storage
     */
    private SearchResult search(Player player, Search search, boolean includeStorage, boolean includeArmor) {
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

        FoundTotem hand = search.candidate(inventory.getHeldItemSlot(),
                inventory.getItem(inventory.getHeldItemSlot()));
        if (hand != null) return search.using(hand);

        FoundTotem offHand = search.candidate(OFF_HAND_SLOT, inventory.getItemInOffHand());
        if (offHand != null) return search.using(offHand);

        if (includeStorage) {
            // Storage = the 36 main slots (hotbar + the three rows), excluding armour and off hand.
            ItemStack[] storage = inventory.getStorageContents();
            for (int slot = 0; slot < storage.length; slot++) {
                FoundTotem found = search.candidate(slot, storage[slot]);
                if (found != null) return search.using(found);
            }
        }

        if (includeArmor) {
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
        private final @Nullable DamageCause cause;
        private final boolean causeMustBeListed;
        private final World.Environment environment;
        private final TotemMode wanted;

        private @Nullable TotemDefinition nearestBlocked;
        private long nearestRemainingMillis = Long.MAX_VALUE;

        private Search(Player player, long now, @Nullable DamageCause cause, boolean causeMustBeListed,
                       World.Environment environment, TotemMode wanted) {
            this.player = player;
            this.wanted = wanted;
            this.now = now;
            this.cause = cause;
            this.causeMustBeListed = causeMustBeListed;
            this.environment = environment;
        }

        /**
         * Decides whether one stack is a totem this player may be saved by, right now, here.
         *
         * <p>Every rejection returns null so the caller's loop falls through to the NEXT totem. That
         * is the correct behaviour for all four rules: a player carrying an on-cooldown {@code
         * ember} and a ready {@code guardian} is saved by the guardian, not killed by the ember.
         */
        private @Nullable FoundTotem candidate(int slot, @Nullable ItemStack stack) {
            if (stack == null || stack.getType().isAir()) return null;

            // The resurrection search keeps its material early-exit: it runs on every lethal hit,
            // and only a Totem of Undying can ever be resurrected with. The keep-inventory search
            // reads tags on every non-empty stack — see searchKeepInventoryTotem for why.
            boolean totemMaterial = isTotemMaterial(stack);
            if (wanted == TotemMode.RESURRECT && !totemMaterial) return null;

            // An untagged totem is the reserved vanilla type by definition, so a server that never
            // stamped anything still gets the configured vanilla behaviour. A type of the OTHER mode
            // resolves to null here and is skipped silently, like any item that is not a totem.
            TotemDefinition definition =
                    totems.registry().resolveFor(stamper.readType(stack), totemMaterial, wanted);
            if (definition == null) return null;

            String permission = definition.permission();
            if (permission != null && !player.hasPermission(permission)) return null;

            // Per-totem world rule. The global one was answered before the walk began, so this is
            // only ever the `per-totem-blacklist` half.
            if (config.worldBlacklist().isBlocked(player.getWorld().getName(), definition.id())) {
                return null;
            }

            // Conditions: this type was never meant for this kind of death. Checked BEFORE the
            // cooldown on purpose — a void-only totem that is also on cooldown must not make a player
            // who just died in lava hear "wait 4m", because no amount of waiting would have let that
            // totem save them from lava. A condition skip is silent, like a permission skip.
            if (!definition.conditions().matches(cause, environment)) return null;

            // The stricter rule for deaths vanilla never lets a totem prevent: matching is not
            // enough, the type must NAME the cause. An unrestricted type matches every cause, and
            // letting it through here would turn every ordinary totem into a void rescue.
            if (causeMustBeListed && !definition.conditions().listsCause(cause)) return null;

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

    /** The off-hand slot in a {@code PlayerInventory}'s flat index space. */
    public static final int OFF_HAND_SLOT = 40;

    /**
     * Whether a flat inventory index is one of the player's two hands.
     *
     * <p>A totem in a hand is the vanilla case and is honoured without the inventory-wide gates; one
     * anywhere else is this plugin's feature and answers to them.
     */
    public static boolean isHandSlot(PlayerInventory inventory, int slot) {
        return slot == inventory.getHeldItemSlot() || slot == OFF_HAND_SLOT;
    }

    /**
     * Which configured type a stack is, regardless of mode, or null when it is not a totem.
     *
     * <p>Used by the resurrect listener to ask what vanilla found in a hand. See {@link
     * TotemRegistry#resolve} for the material rules.
     */
    public @Nullable TotemDefinition typeOf(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        return totems.registry().resolve(stamper.readType(stack), isTotemMaterial(stack));
    }

    /**
     * Every consequence of a keep-inventory totem being spent on a death, other than the event
     * mutations the listener owns: consuming exactly one unit, starting the cooldown, telling the
     * player.
     *
     * <p>No animation and no sound, deliberately. The totem animation means "you did not die", which
     * is the opposite of what just happened, and the plugin's one sound belongs to a resurrection
     * (see {@link SoundService}). The chat line is the whole announcement.
     *
     * <p>Consumption is unconditional: {@code consume: false} is refused for this mode at parse time,
     * because a keeper that is never spent would be permanent keep-inventory in one item.
     */
    public void applyKeepInventory(Player player, FoundTotem found) {
        TotemDefinition definition = found.definition();

        // The inventory is kept, so this unit has to be taken out of it explicitly — otherwise the
        // player respawns holding the very totem that paid for the respawn. Nothing else removes it:
        // the drops list the listener cleared was never the inventory itself.
        consumeOne(player, found);

        cooldowns.startCooldown(player, definition, System.currentTimeMillis());

        if (config.announceToPlayer()) {
            messages.sendPrefixed(player, "totem.inventory_kept",
                    "%totem%", messages.escape(definition.id()));
        }
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

        // Scheduled BEFORE the after-effects, so when both land on the same tick (the default delay
        // is one tick for each) the teleport runs first and the effects are applied to a player who
        // is already safe. The order would be survivable the other way round — potion effects are
        // kept across a teleport, cross-world included — but this way nothing depends on that.
        scheduleRescue(player, definition);
        scheduleAfterEffects(player, definition);
    }

    /**
     * Saves a player from a death vanilla never lets any totem prevent, then applies the totem.
     *
     * <p>Only the void reaches this. In 1.21.11 {@code out_of_world} damage is in the vanilla tag
     * {@code #minecraft:bypasses_invulnerability}, and {@code LivingEntity#checkTotemDeathProtection}
     * returns false for that tag BEFORE it raises {@code EntityResurrectEvent} — so for a void death
     * the resurrect event simply never fires and the normal path cannot see it. The only hook left
     * is the damage event itself, which the caller has just cancelled.
     *
     * <p>Because vanilla's resurrect branch never runs, everything it would have done is done here:
     * the 1 HP, the cleared effects and vanilla's Regeneration II / Absorption II / Fire Resistance
     * (the exact {@code DeathProtection.TOTEM_OF_UNDYING} values), then {@link #applyResurrection}
     * with manual consumption, since no hand was ever emptied.
     *
     * @param lethalDamage the damage of the hit that was cancelled. Recorded as the player's last
     *                     damage together with fresh invulnerability ticks, so the void hits of the
     *                     next few ticks — which keep coming every tick while the player is below
     *                     the world — are ignored until the one-tick-later rescue has moved them
     */
    public void resurrectWithoutVanilla(Player player, FoundTotem found, double lethalDamage) {
        player.setHealth(1.0);

        player.clearActivePotionEffects();
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));

        // Vanilla's invulnerability window only opens on damage that actually landed. The lethal hit
        // was cancelled, so without this the very next tick's void damage lands in full.
        player.setNoDamageTicks(player.getMaximumNoDamageTicks());
        player.setLastDamage(lethalDamage);

        applyResurrection(player, found, true);
    }

    /**
     * Sends a saved player back to solid ground, one tick later, when the type asks for it.
     *
     * <p>One tick later rather than inline, for the same reason the effects wait: this runs inside
     * the damage/resurrect handling of the current tick, and moving an entity in the middle of its
     * own damage processing is asking the rest of that processing to act on a stale position.
     *
     * <p>{@code teleportAsync}, not {@code teleport}: under Folia the synchronous call is refused,
     * and the destination may be in another region entirely. On plain Paper it completes on the
     * main thread just the same. Fall distance and velocity are cleared both before the jump and
     * after it lands — before, so nothing accumulated in the fall is carried; after, because the
     * teleport is exactly where a stale downward velocity would otherwise survive.
     */
    private void scheduleRescue(Player player, TotemDefinition definition) {
        if (!definition.returnToSafeGround()) return;

        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline() || player.isDead()) return;

            Location target = safeGround.rescueTarget(player);
            resetMotion(player);
            player.teleportAsync(target).thenAccept(moved -> {
                if (!moved) return;
                // Back onto the entity's own scheduler: the future may complete on a thread other
                // than the one that owns the player once they have landed.
                player.getScheduler().run(plugin, after -> resetMotion(player), null);
            });
        }, null, 1L);
    }

    private static void resetMotion(Player player) {
        player.setFallDistance(0.0F);
        player.setVelocity(new Vector(0, 0, 0));
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
     * <p>Called on the inventory-wide path, where with {@code getHand() == null} vanilla consumed
     * nothing, and on the keep-inventory path, where no vanilla code consumes anything at all.
     * Leaving it out on either would hand the player an infinite totem.
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
