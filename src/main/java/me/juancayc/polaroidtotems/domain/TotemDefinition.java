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
}
