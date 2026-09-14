package me.juancayc.polaroidtotems.messaging;

import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * The single shared {@link MiniMessage} instance for the whole plugin.
 *
 * <p>{@link MessageService} and {@link ColorFormats} both render through this exact instance, so a
 * tag resolver added here would be visible everywhere at once.
 *
 * <h2>Why no Nexo glyph resolver is registered here</h2>
 *
 * <p>It would be redundant and fragile. Nexo registers its {@code <glyph:id>} tags into Paper's
 * <em>bootstrap tag registry</em> (via {@code NexoTags.registerTags(BootstrapContext)}), which means
 * the server-global MiniMessage already knows them. Any string this plugin deserializes therefore
 * resolves {@code <glyph:totem>} without us importing a single Nexo class.
 *
 * <p>That matters for two reasons:
 * <ul>
 *   <li>If Nexo is absent, the tag degrades to literal text instead of throwing — there is no class
 *       to fail to load, because we never reference one.</li>
 *   <li>This plugin ships <strong>no textures of its own</strong>. Fonts, sprites and resource packs
 *       are produced externally in Nexo; the plugin only passes config strings through.</li>
 * </ul>
 *
 * <p>Do not add a Nexo glyph resolver here. Nexo exposes no {@code NexoGlyphs} class, and binding to
 * an internal one would break the "one jar spans the whole supported range" guarantee.
 */
public final class MiniMessageProvider {

    private static final MiniMessage INSTANCE = MiniMessage.miniMessage();

    private MiniMessageProvider() {}

    public static MiniMessage get() {
        return INSTANCE;
    }
}
