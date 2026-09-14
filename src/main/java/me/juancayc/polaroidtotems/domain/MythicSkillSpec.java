package me.juancayc.polaroidtotems.domain;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One MythicMobs skill cast by a totem on resurrection.
 *
 * <p>Deliberately Bukkit-free and Mythic-free: nothing here imports {@code io.lumine}, so this
 * record parses and unit-tests on a machine that has never seen MythicMobs. The skill NAME is kept
 * as a plain string and only handed to Mythic at cast time — resolving it here would both drag the
 * import into the domain layer and freeze a lookup that Mythic can still answer differently after a
 * {@code /mm reload}.
 *
 * <p>Lives beside {@link TotemEffectSpec} rather than sharing a type with it: the two are parsed
 * from separate YAML lists ({@code effects:} and {@code skills:}) and have nothing in common beyond
 * "a thing that happens one tick after the resurrection".
 *
 * @param skillName  the Mythic skill's internal name, exactly as its own YAML declares it
 * @param power      Mythic's power multiplier, at least 0. 1.0 is the neutral value
 * @param targetSelf whether the reviving player is handed to the skill as its sole entity target;
 *                   false leaves the target list empty so the skill's own {@code @targeter} decides
 */
public record MythicSkillSpec(String skillName, float power, boolean targetSelf) {

    /**
     * Parses the {@code skills:} list of one totem section.
     *
     * <p>Same discipline as {@link TotemEffectSpec#parseList}: an entry that cannot be understood is
     * skipped with a warning rather than failing the totem. A typo in a skill name is the common
     * case here and it must not disable a type a server already hands out — especially since the
     * name cannot be validated at parse time anyway (MythicMobs may not be loaded yet, and its skill
     * list changes on its own reload).
     *
     * @param section   the totem's own section, or null
     * @param path      the key holding the list, normally {@code "skills"}
     * @param onWarning receives a human-readable line per rejected entry
     */
    public static List<MythicSkillSpec> parseList(@Nullable ConfigurationSection section, String path,
                                                  Consumer<String> onWarning) {
        List<MythicSkillSpec> parsed = new ArrayList<>();
        if (section == null) return List.copyOf(parsed);

        List<?> raw = section.getList(path);
        if (raw == null) return List.copyOf(parsed);

        for (Object element : raw) {
            if (!(element instanceof java.util.Map<?, ?> map)) {
                onWarning.accept("Skipping a malformed entry under '" + section.getName() + "."
                        + path + "'; each skill must be a mapping with a `skill:` key.");
                continue;
            }

            Object skillValue = map.get("skill");
            String skillName = skillValue == null ? null : skillValue.toString().trim();
            if (skillName == null || skillName.isEmpty()) {
                onWarning.accept("Skipping a skill entry with no `skill:` name in totem '"
                        + section.getName() + "'.");
                continue;
            }

            // Mythic reads power as a float and multiplies damage/duration terms by it. A negative
            // would invert those inside the skill rather than fail, so it is clamped here instead of
            // being passed through as a surprise.
            float power = (float) Math.max(0.0, toDouble(map.get("power"), 1.0));

            // Defaults to true because a totem skill is overwhelmingly about the player it just
            // saved; a skill with its own @targeter opts out by setting this to false.
            boolean targetSelf = toBoolean(map.get("target-self"), true);

            parsed.add(new MythicSkillSpec(skillName, power, targetSelf));
        }
        return List.copyOf(parsed);
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
