package me.juancayc.polaroidtotems.skill;

import me.juancayc.polaroidtotems.commands.TotemsCommand;
import me.juancayc.polaroidtotems.messaging.MessageService;
import me.juancayc.polaroidtotems.totem.TotemRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Runs {@link SkillValidator} and tells the console and the online admins about it.
 *
 * <p>Split from the validator on purpose: everything here needs a live server (a logger, the player
 * list, a permission check), and keeping it out of the validator is what lets the "which names are
 * broken" logic be unit-tested offline. This class holds no findings of its own — one pass in, one
 * round of output out.
 *
 * <p>Silence is the healthy state. A clean config produces nothing in chat and nothing in the log:
 * a reload that prints "0 problems" every time is a reload whose output stops being read.
 */
public final class SkillValidationReporter {

    /**
     * How long startup validation waits before asking MythicMobs anything, in ticks.
     *
     * <p>One tick would already be enough to clear our own {@code onEnable}, but not necessarily
     * Mythic's: it parses its skill files asynchronously and finishes some time AFTER its enable
     * returns. A second is far past the point where every plugin's {@code onEnable} has run and
     * Mythic has populated its registry, and it is invisible to a server owner watching the boot
     * log. Being early here does not produce a late warning — it produces a wrong one, naming every
     * skill in the file as unknown.
     */
    public static final long STARTUP_DELAY_TICKS = 20L;

    private final Plugin plugin;
    private final SkillValidator validator;
    private final MessageService messages;

    public SkillValidationReporter(Plugin plugin, SkillValidator validator, MessageService messages) {
        this.plugin = plugin;
        this.validator = validator;
        this.messages = messages;
    }

    /**
     * Validates now and reports to the console and to every online admin.
     *
     * @param registry the snapshot to validate — passed in rather than held, because reload swaps it
     * @param origin   the sender who asked for this (the {@code /totems reload} runner), or null on
     *                 the startup path. Used only to avoid messaging an admin twice; see
     *                 {@link #broadcast}
     */
    public void validateAndReport(TotemRegistry registry, @Nullable CommandSender origin) {
        // Mythic absent: no name can be checked, so there is nothing to report as unknown. The one
        // thing worth saying is the installation mismatch — skills configured with nothing to run
        // them — and it is said ONCE at INFO, not as a warning per skill. It is a fact about the
        // server's plugin list, not a mistake in the file, and it must never reach players in chat.
        if (!validator.isMythicPresent()) {
            if (SkillValidator.hasConfiguredSkills(registry)) {
                plugin.getLogger().info("totems.yml configures "
                        + SkillValidator.configuredSkillNames(registry).size()
                        + " MythicMobs skill name(s), but MythicMobs is not installed or not enabled."
                        + " Those skills will be skipped silently until it is.");
            }
            return;
        }

        SkillValidator.Report report = validator.validate(registry);
        if (report.isClean()) return;

        logToConsole(report);
        broadcast(report, origin);
    }

    /**
     * One warning line per finding, naming the totem AND the name, plus how to fix it.
     *
     * <p>Per finding rather than per name, matching {@link SkillValidator.Finding}'s own reasoning:
     * the owner edits a totem entry, so the totem id is the actionable half. Tone kept in line with
     * the warning {@link MythicSkillHook#cast} already emits for the same mistake caught later.
     */
    private void logToConsole(SkillValidator.Report report) {
        for (SkillValidator.Finding finding : report.findings()) {
            plugin.getLogger().warning("Totem '" + finding.totemId() + "' names MythicMobs skill '"
                    + finding.skillName() + "', which MythicMobs does not know. Check the name in"
                    + " totems.yml and run /mm reload.");
        }
    }

    /**
     * Sends the summary and its details to every online admin, and to {@code origin} exactly once.
     *
     * <p>The double-message hazard: {@code /totems reload} run by an op is both the origin AND a
     * holder of {@code polaroidtotems.admin}, so a naive "message the sender, then broadcast to
     * admins" sends them the whole report twice. Collecting recipients into a {@link LinkedHashSet}
     * keyed on the sender identity — origin first, then the online admins — makes the duplicate
     * impossible by construction rather than by a conditional that a later branch can forget.
     *
     * <p>A console origin is included the same way: the report is worth echoing to whoever typed
     * the command even though {@link #logToConsole} already wrote the detail, because the console
     * sender may be a remote RCON session reading only command output.
     */
    private void broadcast(SkillValidator.Report report, @Nullable CommandSender origin) {
        Set<CommandSender> recipients = new LinkedHashSet<>();
        if (origin != null) recipients.add(origin);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(TotemsCommand.PERMISSION_ADMIN)) recipients.add(online);
        }

        for (CommandSender recipient : recipients) {
            messages.sendPrefixed(recipient, "skill.unknown_summary",
                    "%count%", String.valueOf(report.findings().size()));
            for (SkillValidator.Finding finding : report.findings()) {
                // escape() on BOTH values: a totem id and a skill name come straight out of
                // totems.yml, and an id like `<red>` would otherwise recolour an admin's chat
                // (POLAROID-STYLE.md rule 7).
                messages.sendRaw(recipient, "skill.unknown_entry",
                        "%totem%", messages.escape(finding.totemId()),
                        "%skill%", messages.escape(finding.skillName()));
            }
        }
    }
}
