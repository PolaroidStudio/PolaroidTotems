package me.juancayc.polaroidtotems.domain;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * One potion effect granted by a totem on resurrection.
 *
 * <p>{@code amplifier} is stored the way Bukkit wants it — <strong>zero-based</strong>, so
 * amplifier 1 is level II. The config is written in human levels ({@code level: 2}) and converted
 * here exactly once, in {@link #parse}, so no other class has to remember the off-by-one.
 *
 * @param type      the effect to apply
 * @param duration  duration in ticks, at least 1
 * @param amplifier zero-based amplifier (level - 1), at least 0
 * @param ambient   whether the particle haze is the faint "beacon" variant
 * @param particles whether particles are shown at all
 */
public record TotemEffectSpec(PotionEffectType type, int duration, int amplifier,
                              boolean ambient, boolean particles) {

    /** Builds the Bukkit effect this spec describes. */
    public PotionEffect toPotionEffect() {
        return new PotionEffect(type, duration, amplifier, ambient, particles);
    }

    /**
     * Parses the {@code effects:} list of one totem section.
     *
     * <p>An entry with an unknown effect name is skipped with a warning rather than failing the
     * whole totem: one typo in one line must not silently disable a totem type a server already
     * hands out.
     *
     * @param section   the totem's own section, or null
     * @param path      the key holding the list, normally {@code "effects"}
     * @param onWarning receives a human-readable line per rejected entry
     */
    public static List<TotemEffectSpec> parseList(@Nullable ConfigurationSection section, String path,
                                                  Consumer<String> onWarning) {
        List<TotemEffectSpec> parsed = new ArrayList<>();
        if (section == null) return List.copyOf(parsed);

        List<?> raw = section.getList(path);
        if (raw == null) return List.copyOf(parsed);

        for (Object element : raw) {
            if (!(element instanceof java.util.Map<?, ?> map)) {
                onWarning.accept("Skipping a malformed entry under '" + section.getName() + "."
                        + path + "'; each effect must be a mapping with a `type:` key.");
                continue;
            }

            Object typeValue = map.get("type");
            PotionEffectType type = typeValue == null ? null : matchEffect(typeValue.toString());
            if (type == null) {
                onWarning.accept("Skipping unknown effect '" + typeValue + "' in totem '"
                        + section.getName() + "'.");
                continue;
            }

            // seconds in the config, ticks in the API — the conversion lives here and nowhere else.
            double seconds = toDouble(map.get("seconds"), 10.0);
            int duration = Math.max(1, (int) Math.round(seconds * 20.0));

            // Human level in the config (1 = level I), zero-based amplifier in the API.
            int level = Math.max(1, (int) toDouble(map.get("level"), 1.0));
            int amplifier = level - 1;

            boolean ambient = toBoolean(map.get("ambient"), false);
            boolean particles = toBoolean(map.get("particles"), true);

            parsed.add(new TotemEffectSpec(type, duration, amplifier, ambient, particles));
        }
        return List.copyOf(parsed);
    }

    /**
     * Resolves an effect name through the registry.
     *
     * <p>{@code PotionEffectType.getByName} is deprecated; the registry lookup is the supported
     * route and it is what accepts both {@code REGENERATION} and {@code minecraft:regeneration}.
     *
     * <p>The registry is {@code MOB_EFFECT}, not {@code EFFECT}: the latter has been an obsolete
     * alias of it since 1.21.4.
     */
    public static @Nullable PotionEffectType matchEffect(String name) {
        if (name == null || name.isBlank()) return null;
        String cleaned = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        NamespacedKey key = cleaned.contains(":")
                ? NamespacedKey.fromString(cleaned)
                : NamespacedKey.minecraft(cleaned);
        return key == null ? null : Registry.MOB_EFFECT.get(key);
    }

    private static double toDouble(@Nullable Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException notANumber) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean toBoolean(@Nullable Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) return Boolean.parseBoolean(text.trim());
        return fallback;
    }
}
