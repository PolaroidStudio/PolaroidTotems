package me.juancayc.polaroidtotems.util;

import me.juancayc.polaroidtotems.config.ConfigManager;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * One sound for the whole plugin, defined once in {@code config.yml}
 * (POLAROID-STYLE.md: "One sound. One click sound for a whole panel").
 *
 * <p>It plays on a successful resurrection and nowhere else. A refused totem, a failed give or a
 * reload stay silent — a plugin that beeps at every outcome turns into a slot machine.
 */
public final class SoundService {

    private final ConfigManager config;

    public SoundService(ConfigManager config) {
        this.config = config;
    }

    public void playResurrect(Player player) {
        Sound sound = config.resurrectSound();
        if (sound == null) return;
        player.playSound(player.getLocation(), sound, config.resurrectVolume(), config.resurrectPitch());
    }
}
