package me.juancayc.polaroidtotems.skill;

import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.skills.variables.VariableRegistry;
import io.lumine.mythic.core.skills.variables.VariableScope;
import me.juancayc.polaroidtotems.domain.MythicSkillSpec;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/**
 * The ONLY class in this plugin that names a MythicMobs type. {@link MythicSkillHook} calls into it
 * only after checking that MythicMobs is enabled, so on a server without MythicMobs this class is
 * never loaded.
 *
 * <h2>Why this is a separate class</h2>
 *
 * <p>These calls used to live in MythicSkillHook itself, which the plugin constructs on every
 * server. That crashed the plugin on enable wherever MythicMobs was missing
 * ({@code NoClassDefFoundError: io/lumine/mythic/core/skills/placeholders/PlaceholderMeta}): the
 * JVM verifies a whole class when it is first used, and the cast passes a {@code SkillMetadata}
 * where Mythic's API expects a {@code PlaceholderMeta}. Checking that one type fits the other loads
 * both, so merely constructing the hook needed MythicMobs' classes, however carefully every method
 * guarded its calls. {@code join-classpath: true} only helps when MythicMobs is installed.
 */
final class MythicCalls {

    private MythicCalls() {
    }

    static boolean skillExists(String name) {
        return MythicBukkit.inst().getSkillManager().getSkill(name).isPresent();
    }

    /**
     * Casts one skill with the reviving player as its caster.
     *
     * @return whether a skill with that NAME was found (not whether it ran; see MythicSkillHook#cast)
     */
    static boolean cast(Player player, MythicSkillSpec spec, TotemDefinition definition) {
        // An empty target list is meaningful, not a degenerate case: it hands the skill back to its
        // own @targeter instead of forcing the player on it.
        Collection<Entity> entityTargets = spec.targetSelf() ? List.of(player) : List.of();
        return MythicBukkit.inst().getAPIHelper().castSkill(
                player,                 // caster — resolves through PlayerManager into a real
                                        // SkillCaster, which is what makes caster.var.* work
                spec.skillName(),
                player,                 // trigger — the same player; a totem has no other actor
                player.getLocation(),   // origin
                entityTargets,
                List.of(),              // location targets: a totem skill acts on a player, and a
                                        // skill that wants coordinates has @targeters for it
                spec.power(),
                meta -> writeCasterVariables(meta, spec, definition));
    }

    /**
     * Publishes the totem's identity into the skill's placeholder space.
     *
     * <p>The scope matters and is easy to get wrong. {@code meta.getVariables()} is the SKILL-scoped
     * registry, which backs {@code <skill.var.x>}; {@code <caster.var.x>} reads a different registry
     * entirely, and writing to the wrong one produces a silently unresolved placeholder rather than
     * an error. CASTER scope is the right one here because the value describes the caster's totem,
     * and because it stays readable from sub-skills the cast delegates to.
     */
    private static void writeCasterVariables(SkillMetadata meta, MythicSkillSpec spec, TotemDefinition definition) {
        VariableRegistry registry = MythicBukkit.inst().getVariableManager()
                .getRegistry(VariableScope.CASTER, meta, meta.getCaster().getEntity());
        registry.putString("totem_type", definition.id());
        registry.putFloat("totem_power", spec.power());
    }
}
