package me.juancayc.polaroidtotems.item;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Contract for resolving an item reference of one plugin/namespace. */
public interface ItemHook {

    /** The reference prefix, e.g. "nexo". Refs are matched as prefix + "-"/":" + id. */
    String getPrefix();

    /** True when the backing plugin is present, or true for vanilla hooks. */
    boolean isEnabled();

    /** Resolves the id (everything after the separator) to an ItemStack, or null if unknown. */
    @Nullable ItemStack getItem(String id);

    /** Reverse lookup: this hook's id for the given item, or null. */
    default @Nullable String getId(ItemStack item) {
        return null;
    }
}
