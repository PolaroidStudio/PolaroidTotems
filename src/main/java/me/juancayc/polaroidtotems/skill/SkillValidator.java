package me.juancayc.polaroidtotems.skill;

import me.juancayc.polaroidtotems.domain.MythicSkillSpec;
import me.juancayc.polaroidtotems.domain.TotemDefinition;
import me.juancayc.polaroidtotems.totem.TotemRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Checks every configured MythicMobs skill name against Mythic and reports the ones it does not
 * know.
 *
 * <p>A typo'd skill name is invisible until the totem actually saves someone: {@code castSkill}
 * returns false, one warning lands in a log nobody is reading, and the server owner concludes the
 * totem is broken. Validating at (re)load time moves that discovery to the moment the owner is
 * actually editing the file.
 *
 * <p>WHEN this runs is the whole design. MythicMobs populates its skill registry ASYNCHRONOUSLY,
 * after its own {@code onEnable} returns, so a check that runs inside our {@code onEnable} asks
 * Mythic about skills it has not parsed yet and reports EVERY name as unknown. A false positive on
 * that scale is worse than no check at all — it teaches the owner to ignore the warning, which
 * costs them the real one later. Startup validation is therefore deferred (see
 * {@link me.juancayc.polaroidtotems.PolaroidTotemsPlugin}); {@code /totems reload} validates
 * immediately because Mythic is long since loaded by then. Same lazy-resolution doctrine the
 * Nexo item path already follows.
 *
 * <p>{@link #findUnknown} is deliberately pure and free of Bukkit, Mythic and any scheduler: it
 * takes a registry and an "does Mythic know this name" predicate and returns findings. That is what
 * makes the "what is broken" half testable without a server; the broadcasting half lives in the
 * caller.
 */
public final class SkillValidator {

    private final MythicSkillHook skillHook;

    public SkillValidator(MythicSkillHook skillHook) {
        this.skillHook = skillHook;
    }

    /**
     * One configured skill name MythicMobs does not know, and the totem that names it.
     *
     * <p>Per-totem rather than per-name: the owner has to edit a specific entry in {@code
     * totems.yml}, and "skill X is unknown" without a totem id sends them grepping. The same name
     * used by three totems therefore produces three findings even though Mythic was only asked
     * once.
     */
    public record Finding(String totemId, String skillName) {
    }

    /**
     * The outcome of one validation pass.
     *
     * @param checked  how many distinct skill NAMES were put to Mythic. Distinct, not total
     *                 references, because that is the number of lookups actually performed
     * @param findings one entry per (totem, unknown skill) pair, in config order
     */
    public record Report(int checked, List<Finding> findings) {

        public Report {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        /** An empty registry, an absent Mythic, or a clean config all land here. */
        public static Report clean() {
            return new Report(0, List.of());
        }

        public boolean isClean() {
            return findings.isEmpty();
        }
    }

    /**
     * Whether MythicMobs is present and running right now.
     *
     * <p>Exposed rather than left to the caller's own {@code Bukkit} call so the reporter asks the
     * same question through the same hook — and so a test can subclass past it without a server.
     * Re-asked per pass for the reason {@link MythicSkillHook#isEnabled()} documents: Mythic can be
     * disabled or reloaded long after our own enable returned.
     */
    public boolean isMythicPresent() {
        return skillHook.isEnabled();
    }

    /**
     * Validates the given registry against the live MythicMobs.
     *
     * <p>The registry is a PARAMETER rather than a field because it is swapped wholesale on reload;
     * a validator holding its own reference would happily validate the config that was just
     * replaced.
     *
     * <p>Returns a clean report when MythicMobs is absent. A server with no Mythic and no
     * {@code skills:} configured must stay completely silent, and one that does configure skills
     * without Mythic is an installation mismatch, not a bad name — the caller reports that case
     * separately via {@link #hasConfiguredSkills}, once, rather than as a warning per skill.
     */
    public Report validate(TotemRegistry registry) {
        if (!skillHook.isEnabled()) return Report.clean();
        return findUnknown(registry, skillHook::skillExists);
    }

    /**
     * The pure core: which configured names the given oracle rejects.
     *
     * <p>Every distinct name is put to the oracle exactly once and the answer reused, so a shared
     * skill named by twenty totems costs one Mythic lookup rather than twenty. Correctness is not
     * traded for it: the answer cannot change mid-pass, because a pass is a handful of map lookups
     * on the main thread and {@code /mm reload} cannot interleave with it.
     *
     * <p>Package-private only in spirit — it is public so a test can hand it a stub oracle instead
     * of a live server, which is the entire reason the findings logic is separated from the
     * reporting.
     *
     * @param registry the snapshot to walk
     * @param known    answers "does MythicMobs know this name"; must never throw
     */
    public static Report findUnknown(TotemRegistry registry, Predicate<String> known) {
        if (registry == null) return Report.clean();

        // Cached per NAME, not per totem: the same name in three totems is one question with three
        // answers to report.
        Map<String, Boolean> answers = new HashMap<>();
        List<Finding> findings = new ArrayList<>();

        for (TotemDefinition definition : registry.all()) {
            for (MythicSkillSpec spec : definition.skills()) {
                String name = spec.skillName();
                boolean exists = answers.computeIfAbsent(name, SkillValidator.guard(known));
                if (!exists) findings.add(new Finding(definition.id(), name));
            }
        }
        return new Report(answers.size(), findings);
    }

    /**
     * Wraps the oracle so a misbehaving MythicMobs cannot abort the pass.
     *
     * <p>Deliberately {@code Throwable}, matching {@link MythicSkillHook}'s doctrine: this is a
     * third-party plugin being queried, and a {@code LinkageError} from a version mismatch must
     * cost the answer, not the validation. An exception is read as "unknown" — the same conclusion
     * {@link MythicSkillHook#skillExists} already reaches internally, and the honest one, since a
     * name we could not confirm is a name that may well not resolve at cast time either.
     */
    private static java.util.function.Function<String, Boolean> guard(Predicate<String> known) {
        return name -> {
            try {
                return known.test(name);
            } catch (Throwable oracleMisbehaved) {
                return false;
            }
        };
    }

    /**
     * Whether any totem configures a skill at all.
     *
     * <p>Separate from {@link #validate} because it is the one question worth asking when
     * MythicMobs is NOT installed: skills configured with no Mythic to run them is a genuine
     * installation mismatch, while no skills and no Mythic is simply a server that never wanted the
     * feature.
     */
    public static boolean hasConfiguredSkills(TotemRegistry registry) {
        if (registry == null) return false;
        for (TotemDefinition definition : registry.all()) {
            if (!definition.skills().isEmpty()) return true;
        }
        return false;
    }

    /** The distinct skill names the registry configures, in config order. Used for reporting only. */
    public static Set<String> configuredSkillNames(TotemRegistry registry) {
        Set<String> names = new LinkedHashSet<>();
        if (registry == null) return names;
        for (TotemDefinition definition : registry.all()) {
            for (MythicSkillSpec spec : definition.skills()) {
                names.add(spec.skillName());
            }
        }
        return names;
    }
}
