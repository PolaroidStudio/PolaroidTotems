package me.juancayc.polaroidtotems.messaging;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.regex.Pattern;

/**
 * Normalizes every supported Minecraft color/style format to MiniMessage, then renders it.
 *
 * <p>Supported: {@code &f}/{@code §f} legacy codes and styles, {@code &#RRGGBB},
 * {@code §x§R§G§B§R§G§B} legacy hex, {@code #RRGGBB}, {@code <#RRGGBB>} and native MiniMessage tags
 * (passed through unchanged — which is what lets a Nexo {@code <glyph:id>} survive).
 *
 * <p>Regex order is load-bearing: section-hex → section-code → amp-hex → amp-code → bare-hex.
 */
public final class ColorFormats {

    /** The shared instance; see {@link MiniMessageProvider}. */
    private static MiniMessage mm = MiniMessageProvider.get();

    public static void init(MiniMessage instance) {
        mm = instance;
    }

    public static MiniMessage mm() {
        return mm;
    }

    // §x§R§G§B§R§G§B — legacy section hex
    private static final Pattern SECTION_HEX = Pattern.compile("§x(§[0-9a-fA-F]){6}");
    // §f — simple section code
    private static final Pattern SECTION_CODE = Pattern.compile("§([0-9a-fk-orA-FK-OR])");
    // &#RRGGBB — ampersand hex
    private static final Pattern AMP_HEX = Pattern.compile("&#([0-9a-fA-F]{6})");
    // &f — ampersand code
    private static final Pattern AMP_CODE = Pattern.compile("&([0-9a-fk-orA-FK-OR])");
    // #RRGGBB without delimiters — excludes those preceded by &, §, <, # or :
    private static final Pattern BARE_HEX = Pattern.compile("(?<![&§<#:])#([0-9a-fA-F]{6})(?![>0-9a-fA-F])");
    // Color validation: hex or a known MiniMessage color tag
    private static final Pattern VALID_COLOR = Pattern.compile(
            "^(<#[0-9a-fA-F]{6}>|#[0-9a-fA-F]{6}|&#[0-9a-fA-F]{6}|"
                    + "§x(§[0-9a-fA-F]){6}|&[0-9a-fA-FK-OR]|§[0-9a-fA-FK-OR]|"
                    + "<(black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|"
                    + "dark_gray|blue|green|aqua|red|light_purple|yellow|white)>)$",
            Pattern.CASE_INSENSITIVE);

    private ColorFormats() {}

    /** Any color format → its MiniMessage equivalent. Already-valid MiniMessage is unchanged. */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) return input;
        return toLegacyToMM(input);
    }

    /** Parses text with colors in any supported format into a Component. */
    public static Component parse(String text) {
        if (text == null || text.isBlank()) return Component.empty();
        return mm.deserialize(normalize(text));
    }

    /** Serializes a Component back to legacy §-codes. */
    public static String toLegacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    /** True if the whole string is a valid color in any supported format. */
    public static boolean isValidColor(String input) {
        if (input == null || input.isBlank()) return false;
        return VALID_COLOR.matcher(input.trim()).matches();
    }

    /** Converts §-codes / &-codes / bare hex to MiniMessage tags; leaves existing tags intact. */
    public static String toLegacyToMM(String text) {
        if (text == null) return "";
        // Fast path: no legacy/hex markers → nothing to convert.
        if (!text.contains("§") && !text.contains("&") && !text.contains("#")) return text;

        text = SECTION_HEX.matcher(text).replaceAll(m -> {
            String digits = m.group().replace("§x", "").replace("§", "");
            return "<#" + digits + ">";
        });
        text = SECTION_CODE.matcher(text).replaceAll(m -> legacyCodeToTag(m.group(1).charAt(0)));
        text = AMP_HEX.matcher(text).replaceAll("<#$1>");
        text = AMP_CODE.matcher(text).replaceAll(m -> legacyCodeToTag(m.group(1).charAt(0)));
        text = BARE_HEX.matcher(text).replaceAll("<#$1>");
        return text;
    }

    private static String legacyCodeToTag(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'l' -> "<bold>";
            case 'o' -> "<italic>";
            case 'n' -> "<underlined>";
            case 'm' -> "<strikethrough>";
            case 'k' -> "<obfuscated>";
            case 'r' -> "<reset>";
            default -> "&" + code;
        };
    }
}
