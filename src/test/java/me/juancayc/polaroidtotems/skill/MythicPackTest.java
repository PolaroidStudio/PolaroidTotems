package me.juancayc.polaroidtotems.skill;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the bundled MythicMobs pack and the bundled totems.yml in step. A totem naming a PT_ skill
 * the pack does not define would ship a totem that silently casts nothing, and only a console
 * warning on a live server would ever say so.
 */
class MythicPackTest {

    private static YamlConfiguration resource(String path) throws Exception {
        try (InputStream in = MythicPackTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "bundled resource missing: " + path);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        }
    }

    @Test
    @DisplayName("every file the installer copies is bundled and is valid YAML")
    void everyPackFileIsBundled() throws Exception {
        for (String file : MythicPackInstaller.PACK_FILES) {
            resource(MythicPackInstaller.RESOURCE_ROOT + file);
        }
    }

    // The folder names are neutral on purpose: `Packs` and `packs` are one directory on Windows and
    // macOS, so the real pair could only ever be told apart on a Linux build machine.
    @Test
    @DisplayName("a legacy pack is moved with the owner's edits, and its emptied folder is removed")
    void legacyPackIsMovedWithItsEdits(@TempDir Path mythicDir) throws Exception {
        Path legacyDir = mythicDir.resolve("old").resolve("PolaroidTotems");
        Path packDir = mythicDir.resolve("new").resolve("PolaroidTotems");
        Files.createDirectories(legacyDir.resolve("Skills"));
        Files.writeString(legacyDir.resolve("Skills/polaroidtotems.yml"), "edited by the owner");

        MythicPackInstaller.moveLegacyPack(legacyDir, packDir);

        assertEquals("edited by the owner", Files.readString(packDir.resolve("Skills/polaroidtotems.yml")));
        assertFalse(Files.exists(legacyDir.getParent()), "an emptied legacy folder should not linger");
    }

    @Test
    @DisplayName("a legacy folder holding other packs survives the move")
    void legacyFolderWithOtherPacksIsKept(@TempDir Path mythicDir) throws Exception {
        Path legacyDir = mythicDir.resolve("old").resolve("PolaroidTotems");
        Path otherPack = mythicDir.resolve("old").resolve("SomeoneElses");
        Path packDir = mythicDir.resolve("new").resolve("PolaroidTotems");
        Files.createDirectories(legacyDir);
        Files.createDirectories(otherPack);

        MythicPackInstaller.moveLegacyPack(legacyDir, packDir);

        assertTrue(Files.isDirectory(packDir));
        assertTrue(Files.isDirectory(otherPack), "another pack in the legacy folder is not ours to remove");
    }

    @Test
    @DisplayName("every PT_ skill named in totems.yml is defined by the pack")
    void everyReferencedSkillExists() throws Exception {
        Set<String> defined = resource(MythicPackInstaller.RESOURCE_ROOT + "Skills/polaroidtotems.yml")
                .getKeys(false);
        ConfigurationSection totems = resource("totems.yml").getConfigurationSection("totems");
        assertNotNull(totems);

        List<String> referenced = new ArrayList<>();
        for (String id : totems.getKeys(false)) {
            for (Map<?, ?> skill : totems.getMapList(id + ".skills")) {
                Object name = skill.get("skill");
                if (name instanceof String s && s.startsWith("PT_")) referenced.add(s);
            }
        }

        assertFalse(referenced.isEmpty(), "totems.yml should ship totems that use the pack");
        for (String skill : referenced) {
            assertTrue(defined.contains(skill), "totems.yml names " + skill + ", the pack does not define it");
        }
    }

    @Test
    @DisplayName("every PT_ mob summoned by a pack skill is defined by the pack")
    void everySummonedMobExists() throws Exception {
        Set<String> mobs = resource(MythicPackInstaller.RESOURCE_ROOT + "Mobs/polaroidtotems.yml").getKeys(false);
        YamlConfiguration skills = resource(MythicPackInstaller.RESOURCE_ROOT + "Skills/polaroidtotems.yml");

        Pattern summon = Pattern.compile("summon\\{[^}]*type=(PT_\\w+)");
        for (String skill : skills.getKeys(false)) {
            for (String line : skills.getStringList(skill + ".Skills")) {
                Matcher m = summon.matcher(line);
                while (m.find()) {
                    assertTrue(mobs.contains(m.group(1)), skill + " summons " + m.group(1) + ", which is not defined");
                }
            }
        }
    }
}
