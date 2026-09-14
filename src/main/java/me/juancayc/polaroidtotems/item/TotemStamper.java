package me.juancayc.polaroidtotems.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.messaging.MessageService;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The SINGLE point of truth that turns an ItemStack into a totem of a given type.
 *
 * <p>Everything that mints, normalizes or repairs a totem goes through {@link #stamp} — the give
 * command, the normalization listener, and any future feature. Nothing else writes the PDC tag or
 * the stack-size component, so there is exactly one place where the two can drift apart.
 *
 * <h2>Why the PDC tag exists at all</h2>
 *
 * <p>Because a totem's behaviour has to survive being dropped, stored in a chest and traded. The
 * type is written as a plain string under {@link #typeKey()} and read back on the resurrection
 * path; an item with no tag is treated as the reserved {@value TotemDefinition#VANILLA_ID} type.
 *
 * <h2>Why stamping changes stacking</h2>
 *
 * <p>Minecraft decides whether two stacks merge by comparing their <em>components</em>. A plain
 * vanilla totem and one carrying our PDC tag are therefore different items and will never stack
 * with each other, even when both are configured with the same size. That is not a bug to work
 * around but the mechanism itself — it is what lets two totem types coexist with different limits —
 * and it is exactly why {@code NormalizationListener} exists: it stamps untagged totems so a
 * player's inventory converges on one identity instead of a row of singletons.
 */
public final class TotemStamper {

    /** PDC key holding the totem type id. */
    private static final String TYPE_KEY = "totem_type";

    private final Plugin plugin;
    private final ItemManager items;
    private final MessageService messages;
    private final NamespacedKey typeKey;

    public TotemStamper(Plugin plugin, ItemManager items, MessageService messages) {
        this.plugin = plugin;
        this.items = items;
        this.messages = messages;
        this.typeKey = new NamespacedKey(plugin, TYPE_KEY);
    }

    /** The namespaced PDC key this plugin writes the totem type under. */
    public NamespacedKey typeKey() {
        return typeKey;
    }

    /**
     * Mints a fresh item for a totem type.
     *
     * <p>The backing item is resolved here, at use time — never cached at startup, because Nexo
     * loads its items asynchronously and an id that is null during {@code onEnable} resolves
     * perfectly ten seconds later.
     *
     * @return the stamped stack, or null when the type's {@code item:} reference cannot be resolved
     */
    public @Nullable ItemStack create(TotemDefinition definition, int amount) {
        ItemStack base = items.resolve(definition.item());
        if (base == null) return null;
        base.setAmount(Math.max(1, amount));
        stamp(base, definition);
        return base;
    }

    /**
     * Applies a type's identity to an existing stack: the PDC tag, the max stack size, and the
     * optional display name / lore / custom model data.
     *
     * <p>Mutates the stack in place and is idempotent — stamping an already-correct totem produces
     * a byte-identical item, which is what lets the normalization listener run on every inventory
     * click without churning the player's items.
     */
    public void stamp(ItemStack stack, TotemDefinition definition) {
        if (stack == null || stack.getType().isAir()) return;

        // The modern Paper route. `ItemMeta#setMaxStackSize(Integer)` is the Bukkit equivalent and
        // writes the same `minecraft:max_stack_size` component; setData is preferred here because it
        // names the component directly instead of going through a meta round-trip.
        //
        // Range is 1..99 inclusive — TotemDefinition.clampStackSize has already guaranteed that, so
        // no value reaching this line can be refused by the component. A size above 1 is mutually
        // exclusive with `max_damage`, which totems never carry.
        stack.setData(DataComponentTypes.MAX_STACK_SIZE, definition.stackSize());

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(typeKey, PersistentDataType.STRING, definition.id());

        if (definition.displayName() != null) {
            meta.displayName(withoutItalics(messages.render(definition.displayName())));
        }
        if (!definition.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>(definition.lore().size());
            for (String line : definition.lore()) {
                lore.add(withoutItalics(messages.render(line)));
            }
            meta.lore(lore);
        }
        stack.setItemMeta(meta);

        // Both appearance components go on AFTER setItemMeta, through the component API rather than
        // through the meta.
        //
        // `item-model` is the modern route and the one to prefer: it names a model outright
        // (`minecraft:item/my_totem`, or a pack's own namespace), so a resource pack maps the key
        // to a model directly. Since 1.21.4 that replaced the old trick of overloading a number.
        //
        // `custom-model-data` is the legacy route, kept because plenty of existing packs still
        // select on it. `ItemMeta#setCustomModelData(Integer)` has been deprecated since 1.21.5 in
        // favour of this component, whose javadoc states the equivalence exactly: an integer from
        // the old API is a single float in the component's `floats` list.
        //
        // The two are independent components and a server owner may set either, both, or neither.
        // When both are present the client resolves `item_model` first, so the pack decides which
        // one wins — the plugin does not arbitrate.
        if (definition.itemModel() != null) {
            Key modelKey = parseModelKey(definition.itemModel());
            if (modelKey != null) {
                stack.setData(DataComponentTypes.ITEM_MODEL, modelKey);
            }
        }
        if (definition.customModelData() != null) {
            stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                    CustomModelData.customModelData().addFloat(definition.customModelData()));
        }
    }

    /**
     * Parses an {@code item-model} reference into a namespaced key.
     *
     * <p>A bare value such as {@code item/my_totem} takes the {@code minecraft} namespace, matching
     * how vanilla reads the component. An invalid key is refused with a warning rather than thrown:
     * one malformed entry in {@code totems.yml} must not stop the other totems from being handed
     * out, and {@link Key#key(String)} would otherwise throw at give time.
     *
     * @return the parsed key, or null when the value cannot form one
     */
    private @Nullable Key parseModelKey(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) return null;
        try {
            return Key.key(value);
        } catch (InvalidKeyException invalid) {
            plugin.getLogger().warning("Invalid item-model '" + raw
                    + "'; expected a key such as 'minecraft:item/my_totem'. Ignoring it.");
            return null;
        }
    }

    /**
     * Reads the totem type stamped on a stack.
     *
     * @return the type id, or null when the item carries no tag (which the caller must read as the
     *         reserved vanilla type, not as "not a totem")
     */
    public @Nullable String readType(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) return null;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    /** True when the stack already carries this plugin's type tag. */
    public boolean isTagged(@Nullable ItemStack stack) {
        return readType(stack) != null;
    }

    /**
     * Items rendered from a config string must not inherit vanilla's default italics, which would
     * make every custom totem look like a renamed item.
     */
    private static Component withoutItalics(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Exposed for diagnostics; the plugin instance the PDC namespace belongs to. */
    public Plugin plugin() {
        return plugin;
    }
}
