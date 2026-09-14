package me.juancayc.polaroidtotems.item.hooks;

import me.juancayc.polaroidtotems.item.ItemHook;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Explicit vanilla material references: {@code vanilla:TOTEM_OF_UNDYING}. A bare
 * {@code TOTEM_OF_UNDYING} already falls through to {@link org.bukkit.Material#matchMaterial} in the
 * manager, so this hook exists mainly to disambiguate a material whose name could collide with a
 * custom-item prefix.
 *
 * <p>It is also what makes the plain {@code item: vanilla} default in {@code totems.yml} work: the
 * totem registry rewrites that bare keyword to {@code vanilla:TOTEM_OF_UNDYING} before resolving.
 */
public final class VanillaItemHook implements ItemHook {

    @Override
    public String getPrefix() {
        return "vanilla";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public @Nullable ItemStack getItem(String id) {
        Material material = Material.matchMaterial(id.toUpperCase(Locale.ROOT));
        return material != null ? new ItemStack(material) : null;
    }
}
