package me.juancayc.polaroidtotems.item.hooks;

import com.nexomc.nexo.api.NexoItems;
import me.juancayc.polaroidtotems.item.ItemHook;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * The ONLY class in this plugin that imports Nexo. Isolating the import here means core code can
 * never trigger a {@code NoClassDefFoundError} on a server without Nexo installed — the class is
 * loaded only when {@link me.juancayc.polaroidtotems.item.ItemManager} actually dispatches a
 * {@code nexo:} reference to it.
 *
 * <p>Nexo is used for ITEMS only, so a totem type can be backed by a Nexo-textured item instead of
 * the vanilla totem model. Glyphs need nothing from here: Nexo registers {@code <glyph:id>} with
 * Paper's bootstrap tag registry, so the shared MiniMessage resolves them already. See
 * {@link me.juancayc.polaroidtotems.messaging.MiniMessageProvider}.
 */
public final class NexoItemHook implements ItemHook {

    @Override
    public String getPrefix() {
        return "nexo";
    }

    @Override
    public boolean isEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("Nexo");
    }

    /**
     * Resolved lazily on every call. Nexo loads items asynchronously, so caching the result at
     * startup would freeze a null for ids that become valid moments later.
     */
    @Override
    public @Nullable ItemStack getItem(String id) {
        var builder = NexoItems.itemFromId(id);
        return builder != null ? builder.build() : null;
    }

    @Override
    public @Nullable String getId(ItemStack item) {
        return NexoItems.idFromItem(item);
    }
}
