package me.juancayc.polaroidtotems.item;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prefix-based item resolver: {@code nexo:my_totem}, {@code nexo-my_totem}, or a plain vanilla
 * material.
 *
 * <p>Resolution is <strong>lazy and on demand</strong> — never cached at startup. Nexo loads its
 * items asynchronously, so {@code NexoItems.itemFromId} called during {@code onEnable} can return
 * null for an id that resolves perfectly ten seconds later. Every call site here runs when a totem
 * is actually minted by {@code /totems give}, never while the config is being read.
 */
public final class ItemManager {

    private final List<ItemHook> hooks = new ArrayList<>();

    public void registerHook(ItemHook hook) {
        hooks.add(hook);
    }

    /**
     * Resolves "prefix:id" / "prefix-id" via a hook, or a vanilla material name.
     *
     * @return null when the reference cannot be resolved (unknown id, or the backing plugin is
     *         absent). Callers must handle null; this never throws.
     */
    public @Nullable ItemStack resolve(String reference) {
        if (reference == null || reference.isBlank()) return null;
        Match match = match(reference);
        if (match != null) {
            return match.hook.isEnabled() ? match.hook.getItem(match.id) : null;
        }
        Material material = Material.matchMaterial(reference.toUpperCase(Locale.ROOT));
        return material != null ? new ItemStack(material) : null;
    }

    private @Nullable Match match(String reference) {
        for (ItemHook hook : hooks) {
            String name = hook.getPrefix();
            // Accept BOTH separators; the id keeps any further '-'/':'.
            if (reference.startsWith(name + "-") || reference.startsWith(name + ":")) {
                return new Match(hook, reference.substring(name.length() + 1));
            }
        }
        return null;
    }

    private record Match(ItemHook hook, String id) {}
}
