package me.juancayc.polaroidtotems.domain;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One totem type, exactly as {@code totems.yml} describes it.
 *
 * <p>Immutable and Bukkit-free apart from the effect specs, so the whole registry can be swapped
 * atomically on reload and the parsing can be unit-tested without a server.
 *
 * @param id             the type key, also the value written into the item's PDC. The reserved id
 *                       {@value #VANILLA_ID} describes the plain Totem of Undying.
 * @param displayName    MiniMessage name applied to the item, or null to keep the item's own name
 * @param lore           MiniMessage lore lines; empty means "leave the item's lore alone"
 * @param item           item-resolver reference ({@code vanilla}, {@code vanilla:TOTEM_OF_UNDYING},
 *                       {@code nexo:my_totem}). Resolved LAZILY at use time, never at startup.
 * @param stackSize      the {@code minecraft:max_stack_size} value for this type, already clamped
 *                       to {@link #MIN_STACK_SIZE}..{@link #MAX_STACK_SIZE}
 * @param cooldownSeconds how long this player must wait before this type can save them again, in
 *                       seconds, already clamped to at least {@link #NO_COOLDOWN}. Per player per
 *                       type, never global: two players share nothing, and a player on cooldown for
 *                       one type may still be saved by another. {@link #NO_COOLDOWN} disables it
 * @param itemModel      the {@code minecraft:item_model} value: a namespaced key naming the model
 *                       to render, or null to leave it untouched. This is the modern way to give a
 *                       totem its own look — see {@link #itemModel()}.
 * @param customModelData custom model data to stamp, or null to leave it untouched. Legacy route,
 *                       kept for resource packs that still select on it
 * @param effects        potion effects granted one tick after the resurrection
 * @param skills         MythicMobs skills cast one tick after the resurrection. A separate list
 *                       from {@code effects} because the two are unrelated: potion effects are
 *                       vanilla and always available, skills need a third-party plugin and are
 *                       silently skipped without it
 * @param consume        whether the totem is removed from the inventory when it saves the player
 * @param healToFull     whether the player is healed to their max health instead of vanilla's 1 HP
 * @param permission     permission required for this type to fire, or null for "anyone"
 */
public record TotemDefinition(String id,
                              @Nullable String displayName,
                              List<String> lore,
                              String item,
                              int stackSize,
                              long cooldownSeconds,
                              @Nullable String itemModel,
                              @Nullable Integer customModelData,
                              List<TotemEffectSpec> effects,
                              List<MythicSkillSpec> skills,
                              boolean consume,
                              boolean healToFull,
                              @Nullable String permission) {

    /**
     * The reserved id for a plain Totem of Undying.
     *
     * <p>Every totem found without a PDC type tag is treated as this type — that is the rule that
     * lets a server owner give the ordinary vanilla totem its own stack size without the plugin
     * having to stamp every totem that ever existed on the server.
     */
    public static final String VANILLA_ID = "vanilla";

    /**
     * Lower bound of the {@code minecraft:max_stack_size} data component.
     *
     * <p>Vanilla rejects 0 and negatives outright.
     */
    public static final int MIN_STACK_SIZE = 1;

    /**
     * Upper bound of the {@code minecraft:max_stack_size} data component.
     *
     * <p>99 is the vanilla maximum; a larger value is refused by the component itself, so the
     * plugin clamps and warns rather than letting the server throw at give time.
     */
    public static final int MAX_STACK_SIZE = 99;

    /**
     * The value that means "this type has no cooldown at all".
     *
     * <p>Zero rather than a sentinel like -1, because zero is what a server owner naturally writes
     * to turn a cooldown off and it is also what an absent key parses to. Keeping the two identical
     * means {@code cooldown: 0} and no {@code cooldown:} line behave the same way, which is the only
     * behaviour a reader of the file would predict.
     */
    public static final long NO_COOLDOWN = 0L;

    public TotemDefinition {
        lore = lore == null ? List.of() : List.copyOf(lore);
        effects = effects == null ? List.of() : List.copyOf(effects);
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    /** True for the reserved entry describing the plain Totem of Undying. */
    public boolean isVanilla() {
        return VANILLA_ID.equals(id);
    }

    /**
     * Clamps a configured stack size into the component's legal range.
     *
     * <p>Pure and static so it can be tested directly. The warning consumer receives one line per
     * value that had to be moved, naming the totem so a server owner can find the offending entry.
     *
     * <p>Note on the exclusivity rule: a {@code max_stack_size} above 1 is mutually exclusive with
     * {@code max_damage}. Totems carry no durability, so the plugin never sets {@code max_damage}
     * and the two can never collide here.
     */
    public static int clampStackSize(int configured, String totemId, java.util.function.Consumer<String> onWarning) {
        if (configured < MIN_STACK_SIZE) {
            onWarning.accept("Totem '" + totemId + "' has stack-size " + configured
                    + ", below the minimum of " + MIN_STACK_SIZE + "; using " + MIN_STACK_SIZE + ".");
            return MIN_STACK_SIZE;
        }
        if (configured > MAX_STACK_SIZE) {
            onWarning.accept("Totem '" + totemId + "' has stack-size " + configured
                    + ", above the maximum of " + MAX_STACK_SIZE + "; using " + MAX_STACK_SIZE + ".");
            return MAX_STACK_SIZE;
        }
        return configured;
    }

    /**
     * Clamps a configured cooldown into its legal range: anything below zero becomes
     * {@link #NO_COOLDOWN}.
     *
     * <p>Pure and static for the same reason as {@link #clampStackSize}: it is the one piece of the
     * cooldown feature that can be proven correct without a server, and it runs at parse time where
     * a warning is still attached to the file the owner is editing.
     *
     * <p>Only the lower bound is enforced. There is deliberately no upper one — a "once per week"
     * totem is a legitimate design, and the arithmetic has room to spare: expiry is stored as epoch
     * millis in a {@code long}, so even {@link Long#MAX_VALUE} seconds is the only value that could
     * overflow the multiplication, and {@link #cooldownMillis()} saturates rather than wrapping.
     *
     * @param configured the raw {@code cooldown:} value in seconds
     * @param totemId    named in the warning so a server owner can find the offending entry
     * @param onWarning  receives one line per value that had to be moved
     */
    public static long clampCooldownSeconds(long configured, String totemId,
                                            java.util.function.Consumer<String> onWarning) {
        if (configured < NO_COOLDOWN) {
            onWarning.accept("Totem '" + totemId + "' has cooldown " + configured
                    + ", which is negative; treating it as " + NO_COOLDOWN + " (no cooldown).");
            return NO_COOLDOWN;
        }
        return configured;
    }

    /** True when this type is allowed to save the same player again immediately. */
    public boolean hasCooldown() {
        return cooldownSeconds > NO_COOLDOWN;
    }

    /**
     * The configured cooldown as milliseconds, saturating instead of overflowing.
     *
     * <p>Expiry timestamps are epoch millis, so the seconds have to be multiplied by 1000 somewhere.
     * A server owner who writes an absurd {@code cooldown:} (a pasted timestamp, say) would otherwise
     * wrap the {@code long} into a NEGATIVE expiry, which reads as "expired long ago" and silently
     * disables the very cooldown they were trying to make eternal. Saturating turns that typo into
     * "effectively forever", which is at least the direction they meant.
     */
    public long cooldownMillis() {
        return cooldownSeconds > Long.MAX_VALUE / 1000L
                ? Long.MAX_VALUE
                : cooldownSeconds * 1000L;
    }
}
