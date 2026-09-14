package me.juancayc.polaroidtotems.totem;

import me.juancayc.polaroidtotems.config.ConfigManager;
import me.juancayc.polaroidtotems.config.TotemsConfig;
import me.juancayc.polaroidtotems.domain.MythicSkillSpec;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.domain.TotemEffectSpec;
import me.juancayc.polaroidtotems.item.TotemStamper;
import me.juancayc.polaroidtotems.messaging.MessageService;
import me.juancayc.polaroidtotems.skill.MythicSkillHook;
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

    public TotemService(Plugin plugin, ConfigManager config, TotemsConfig totems,
                        TotemStamper stamper, MessageService messages, SoundService sounds,
                        MythicSkillHook skills) {
        this.plugin = plugin;
        this.config = config;
        this.totems = totems;
        this.stamper = stamper;
        this.messages = messages;
        this.sounds = sounds;
        this.skills = skills;
    }

    /** One totem found somewhere in an inventory, with the slot it was found in. */
    public record FoundTotem(int slot, ItemStack stack, TotemDefinition definition) {}

    /**
     * Finds the first usable totem anywhere in the player's inventory.
     *
     * <p>Search order is deliberate and stable: main hand, off hand, then the storage slots in
     * index order, then armour when {@code activation.include-armor-slots} is on. A player who
     * wants a specific totem used first puts it in their hand, exactly as in vanilla.
     *
     * @return null when nothing usable was found — no totem at all, or only totems whose type
     *         requires a permission the player lacks
     */
    public @Nullable FoundTotem findUsableTotem(Player player) {
        PlayerInventory inventory = player.getInventory();

        FoundTotem hand = candidate(player, inventory.getHeldItemSlot(), inventory.getItem(inventory.getHeldItemSlot()));
        if (hand != null) return hand;

        // 40 is the off-hand slot index in a PlayerInventory.
        FoundTotem offHand = candidate(player, 40, inventory.getItemInOffHand());
        if (offHand != null) return offHand;

        // Storage = the 36 main slots (hotbar + the three rows), excluding armour and off hand.
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            FoundTotem found = candidate(player, slot, storage[slot]);
            if (found != null) return found;
        }

        if (config.activateFromArmor()) {
            ItemStack[] armor = inventory.getArmorContents();
            for (int index = 0; index < armor.length; index++) {
                // Armour slots start at 36 in a PlayerInventory's flat index space.
                FoundTotem found = candidate(player, 36 + index, armor[index]);
                if (found != null) return found;
            }
        }
        return null;
    }

    private @Nullable FoundTotem candidate(Player player, int slot, @Nullable ItemStack stack) {
        if (!isTotemMaterial(stack)) return null;

        // An untagged totem is the reserved vanilla type by definition, so a server that never
        // stamped anything still gets the configured vanilla behaviour.
        TotemDefinition definition = totems.registry().resolveOrVanilla(stamper.readType(stack));

        String permission = definition.permission();
        if (permission != null && !player.hasPermission(permission)) return null;

        return new FoundTotem(slot, stack, definition);
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
