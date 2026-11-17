package me.juancayc.polaroidtotems.skill;

import me.juancayc.polaroidtotems.domain.MythicSkillSpec;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * MythicMobs skills cast when a totem saves a player. Constructed on every server; it names no
 * MythicMobs type itself. Every call checks {@link #isEnabled()} first and only then reaches
 * {@link MythicCalls}, the one class that does, so a server without MythicMobs never loads it.
 * See MythicCalls for why the split is needed. This is the same doctrine as
 * {@link me.juancayc.polaroidtotems.item.hooks.NexoItemHook}, for the same reason.
 *
 * <p>MythicMobs is used for SKILLS only: a totem type may name skills that are cast on the player
 * it just saved, which is the supported way to attach behaviour this plugin has no business
 * implementing itself (particles, sounds, area damage, summons, aura application…).
 *
 * <p>Nothing here caches a Mythic object. {@code /mm reload} rebuilds the skill registry wholesale,
 * so a cached {@code Skill} or {@code BukkitAPIHelper} would go stale exactly when a server owner
 * was iterating on their skill file. Every call re-enters through {@code MythicBukkit.inst()}.
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
            return MythicCalls.skillExists(name);
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
     * are written so one shared skill can branch on which totem invoked it.
     */
    public void cast(Player player, MythicSkillSpec spec, TotemDefinition definition) {
        // Absent MythicMobs is the normal case, not an error: the `skills:` key is optional and a
        // server that never installed Mythic simply gets nothing. Returning here, before
        // MythicCalls is touched, is what keeps every MythicMobs class unloaded on such a server.
        if (!isEnabled()) return;
        try {
            boolean skillFound = MythicCalls.cast(player, spec, definition);

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
}
