package me.juancayc.polaroidtotems.skill;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Copies the bundled MythicMobs pack into {@code plugins/MythicMobs/packs/PolaroidTotems/}.
 *
 * <h2>Why a pack</h2>
 *
 * <p>The base totems in totems.yml cast skills (PT_Phoenix, PT_Vampire, ...) that have to exist in
 * MythicMobs before they can fire. MythicMobs loads any folder under {@code packs/} as a
 * self-contained pack with its own {@code Skills/} and {@code Mobs/}, so shipping one means the
 * owner installs nothing by hand and the skills never mix with their own files.
 *
 * <h2>Why onLoad</h2>
 *
 * <p>Every plugin's {@code onLoad} runs before any plugin's {@code onEnable}, and MythicMobs reads
 * its packs while enabling. Copying here is what makes the skills available on the very first start
 * instead of after a second restart or a {@code /mm reload}. It is also why this class reads the
 * switch straight from the file: the ConfigManager does not exist yet.
 *
 * <h2>Never overwrite</h2>
 *
 * <p>The pack is copied only when its folder is missing. Once it exists it belongs to the owner, who
 * may have tuned damage or particles; an update of this plugin must not silently undo that.
 *
 * <h2>Legacy folder</h2>
 *
 * <p>Earlier builds installed into {@code Packs/} with a capital P, which MythicMobs does not read
 * on a case-sensitive filesystem. A pack found there is moved rather than reinstalled, for the same
 * reason it is never overwritten: it may carry the owner's edits.
 */
public final class MythicPackInstaller {

    private static final String PACK_NAME = "PolaroidTotems";
    /**
     * Where earlier builds installed the pack. MythicMobs reads {@code packs/}, so on a
     * case-sensitive filesystem a pack left here is never loaded.
     */
    private static final String LEGACY_PACKS_FOLDER = "Packs";
    static final String RESOURCE_ROOT = "mythic/" + PACK_NAME + "/";

    /**
     * Every file of the pack, relative to the pack root. Listed by hand because a jar's directories
     * cannot be enumerated through the class loader; add a line here when a file is added.
     * MythicPackTest fails if a listed file is not bundled.
     */
    static final List<String> PACK_FILES = List.of(
            "packinfo.yml",
            "Skills/polaroidtotems.yml",
            "Mobs/polaroidtotems.yml");

    private final JavaPlugin plugin;

    public MythicPackInstaller(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void installIfMissing() {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) return;
        if (!installEnabled()) return;

        Path mythicDir = plugin.getDataFolder().toPath().getParent().resolve("MythicMobs");
        Path packDir = mythicDir.resolve("packs").resolve(PACK_NAME);
        // On a case-insensitive filesystem this also matches a pack under the legacy folder, which
        // MythicMobs reads fine there; only a case-sensitive one gets past this line with one.
        if (Files.exists(packDir)) return;

        Path legacyDir = mythicDir.resolve(LEGACY_PACKS_FOLDER).resolve(PACK_NAME);
        if (Files.isDirectory(legacyDir)) {
            try {
                moveLegacyPack(legacyDir, packDir);
                plugin.getLogger().info("Moved the MythicMobs skill pack from " + legacyDir + " to "
                        + packDir + ", where MythicMobs reads it. Your edits were kept.");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not move the MythicMobs skill pack from " + legacyDir
                        + " to " + packDir + ": " + e.getMessage()
                        + ". Move it by hand; until then its skills will not cast.");
            }
            return;
        }

        try {
            for (String file : PACK_FILES) {
                Path target = packDir.resolve(file);
                Files.createDirectories(target.getParent());
                try (InputStream in = plugin.getResource(RESOURCE_ROOT + file)) {
                    if (in == null) {
                        throw new IOException("bundled resource missing: " + RESOURCE_ROOT + file);
                    }
                    Files.copy(in, target);
                }
            }
            plugin.getLogger().info("Installed the MythicMobs skill pack at " + packDir
                    + ". Edit it freely; it is never overwritten.");
        } catch (IOException e) {
            plugin.getLogger().warning("Could not install the MythicMobs skill pack at " + packDir
                    + ": " + e.getMessage() + ". Totems still work; their skills will not cast.");
        }
    }

    /**
     * Moves a pack installed under the legacy folder to where MythicMobs reads it. The emptied
     * legacy folder is removed too; when it holds anything else, that is the owner's and it stays.
     */
    static void moveLegacyPack(Path legacyDir, Path packDir) throws IOException {
        Files.createDirectories(packDir.getParent());
        Files.move(legacyDir, packDir);
        try {
            Files.delete(legacyDir.getParent());
        } catch (IOException ignored) {
            // Not empty, or not ours to delete. The pack itself already moved, which is the point.
        }
    }

    /** Reads {@code mythicmobs.install-pack} from config.yml on disk; true when absent. */
    private boolean installEnabled() {
        File config = new File(plugin.getDataFolder(), "config.yml");
        if (!config.isFile()) return true;
        return YamlConfiguration.loadConfiguration(config).getBoolean("mythicmobs.install-pack", true);
    }
}
