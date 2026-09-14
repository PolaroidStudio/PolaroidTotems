package me.juancayc.polaroidtotems.skill;

import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.skills.variables.VariableRegistry;
import io.lumine.mythic.core.skills.variables.VariableScope;
import me.juancayc.polaroidtotems.domain.MythicSkillSpec;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;

/**
 * The ONLY class in this plugin that imports MythicMobs. Isolating the import here means core code
 * can never trigger a {@code NoClassDefFoundError} on a server without MythicMobs installed — the
 * class is loaded only when {@link #cast} is actually reached, and every entry point guards itself
 * with {@link #isEnabled()} first. This is the same doctrine as
 * {@link me.juancayc.polaroidtotems.item.hooks.NexoItemHook}, for the same reason.
 *
 * <p>MythicMobs is used for SKILLS only: a totem type may name skills that are cast on the player
 * it just saved, which is the supported way to attach behaviour this plugin has no business
 * implementing itself (particles, sounds, area damage, summons, aura application…).
 *
 * <p>Nothing here caches a Mythic object. {@code /mm reload} rebuilds the skill registry wholesale,
 * so a cached {@code Skill} or {@code BukkitAPIHelper} would go stale exactly when a server owner
 * was iterating on their skill file. Every call re-enters through {@link MythicBukkit#inst()}.
 */
public final class MythicSkillHook {

    private final Plugin plugin;

    public MythicSkillHook(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Whether MythicMobs is present and running right now.
     *
     * <p>Asked on every call rather than cached at startup: MythicMobs can be disabled by another
     * plugin, or reloaded out from under us, long after our own {@code onEnable} returned.
     */
    public boolean isEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("MythicMobs");
    }

    /**
     * Whether Mythic currently knows a skill by that name.
     *
     * <p>There is no {@code hasSkill}; the supported route is the {@code Optional} that
     * {@code SkillManager#getSkill} returns. Used for diagnostics only (a command or a warning) —
     * never as a gate before {@link #cast}, because the answer can change between the two calls.
     */
    public boolean skillExists(String name) {
        if (!isEnabled()) return false;
        try {
            return MythicBukkit.inst().getSkillManager().getSkill(name).isPresent();
        } catch (Throwable mythicMisbehaved) {
            return false;
        }
    }

    /**
     * Casts one configured skill with the reviving player as its caster.
     *
     * <p>MUST run on the main/region thread that owns the player. {@code castSkill} does no
     * scheduling of its own — it calls {@code Skill#execute} directly on the calling thread — so
     * calling it from anywhere else corrupts entity state rather than merely being slow. The caller
     * ({@link me.juancayc.polaroidtotems.totem.TotemService}) satisfies this by casting from inside
     * the player's own entity-scheduler task.
     *
     * <p>Skill-visible variables: {@code <caster.var.totem_type>} and {@code <caster.var.totem_power>}
     * are written here so one shared skill can branch on which totem invoked it.
     */
    public void cast(Player player, MythicSkillSpec spec, TotemDefinition definition) {
        // Absent MythicMobs is the normal case, not an error: the `skills:` key is optional and a
        // server that never installed Mythic simply gets nothing. Returning before touching any
        // io.lumine type is what keeps this class from being loaded at all on such a server.
        if (!isEnabled()) return;

        // An empty target list is meaningful, not a degenerate case: it hands the skill back to its
        // own @targeter instead of forcing the player on it.
        Collection<Entity> entityTargets = spec.targetSelf() ? List.of(player) : List.of();

        try {
            boolean skillFound = MythicBukkit.inst().getAPIHelper().castSkill(
                    player,                 // caster — resolves through PlayerManager into a real
                                            // SkillCaster, which is what makes caster.var.* work
                    spec.skillName(),
                    player,                 // trigger — the same player; a totem has no other actor
                    player.getLocation(),   // origin
                    entityTargets,
                    List.of(),              // location targets: a totem skill acts on a player, and
                                            // a skill that wants coordinates has @targeters for it
                    spec.power(),
                    meta -> writeCasterVariables(meta, spec, definition));

            // The returned boolean means "a skill with that NAME was found", NOT "the skill ran".
            // Mythic returns true even when the skill's own conditions reject it via isUsable(), so
            // it is only ever safe to read in the negative: false proves the name is wrong, true
            // proves nothing. Reporting it as success would tell a server owner their skill fired
            // when its conditions had silently refused it.
            if (!skillFound) {
                plugin.getLogger().warning("Totem '" + definition.id() + "' names MythicMobs skill '"
                        + spec.skillName() + "', which MythicMobs does not know. Check the skill's"
                        + " name and run /mm reload.");
            }
        } catch (Throwable mythicMisbehaved) {
            // Deliberately Throwable, not Exception. This is a third-party plugin being called from
            // the middle of a resurrection: whatever it throws — including a LinkageError from a
            // version mismatch, or an Error out of its own scheduler — must not abort the remaining
            // consequences (the other skills, and anything a later caller adds after them). The
            // player has already been saved; a broken skill must not unsave them.
            plugin.getLogger().warning("MythicMobs threw while casting skill '" + spec.skillName()
                    + "' for totem '" + definition.id() + "': " + mythicMisbehaved);
        }
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
    private static void writeCasterVariables(SkillMetadata meta, MythicSkillSpec spec,
                                             TotemDefinition definition) {
        VariableRegistry registry = MythicBukkit.inst().getVariableManager()
                .getRegistry(VariableScope.CASTER, meta, meta.getCaster().getEntity());
        registry.putString("totem_type", definition.id());
        registry.putFloat("totem_power", spec.power());
    }
}
