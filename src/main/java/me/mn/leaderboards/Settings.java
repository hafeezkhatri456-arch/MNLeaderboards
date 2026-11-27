package me.mn.leaderboards;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

/** Values from config.yml "settings:". */
final class Settings {
    final double viewDistance;
    final int positions;
    final int refreshSeconds;
    final int inactiveDays;
    final int batchSize;

    final float rowSpacing;
    final int rowWidth;
    final int iconWidth;
    final boolean heads;
    final float textScale;

    final int typingTicks;
    final int rowDelay;
    final boolean shimmer;
    final boolean youLine;
    final Material panelMaterial;
    final boolean panelGlow;
    final boolean panelEnabled;
    final float titleScale;
    final boolean circled;
    final boolean valueIcons;
    final boolean dots;

    final boolean typingSound;
    final String typingKey;
    final float typingVolume;
    final float typingPitch;
    final boolean rowSound;
    final String rowKey;
    final float rowVolume;
    final float rowPitchStart;
    final float rowPitchStep;

    Settings(FileConfiguration c) {
        viewDistance = Math.max(8, c.getDouble("settings.view-distance", 40));
        positions = Math.max(1, Math.min(20, c.getInt("settings.positions", 10)));
        refreshSeconds = Math.max(5, c.getInt("settings.refresh-seconds", 60));
        inactiveDays = Math.max(0, c.getInt("settings.ignore-inactive-days", 0));
        batchSize = Math.max(1, c.getInt("settings.batch-size", 25));

        rowSpacing = (float) c.getDouble("settings.row-spacing", 0.30);
        rowWidth = Math.max(60, c.getInt("settings.row-width", 150));
        iconWidth = Math.max(0, c.getInt("settings.icon-width", 9));
        heads = c.getBoolean("settings.heads", true);
        textScale = (float) Math.max(0.3, c.getDouble("settings.text-scale", 1.0));

        typingTicks = Math.max(1, c.getInt("settings.typing-ticks-per-char", 1));
        rowDelay = Math.max(1, c.getInt("settings.row-delay-ticks", 3));
        shimmer = c.getBoolean("settings.shimmer", true);
        youLine = c.getBoolean("settings.you-line", true);

        Material m = Material.matchMaterial(c.getString("settings.panel.material", "BLACK_CONCRETE"));
        panelMaterial = (m != null && m.isBlock()) ? m : Material.BLACK_CONCRETE;
        panelGlow = c.getBoolean("settings.panel.glow", true);
        panelEnabled = c.getBoolean("settings.panel.enabled", false);
        titleScale = (float) Math.max(0.5, c.getDouble("settings.title-scale", 2.0));
        circled = c.getBoolean("settings.circled-ranks", true);
        valueIcons = c.getBoolean("settings.value-icons", true);
        dots = c.getBoolean("settings.dots", false);

        typingSound = c.getBoolean("settings.sounds.typing.enabled", true);
        typingKey = c.getString("settings.sounds.typing.key", "minecraft:block.note_block.hat");
        typingVolume = (float) c.getDouble("settings.sounds.typing.volume", 0.35);
        typingPitch = (float) c.getDouble("settings.sounds.typing.pitch", 1.7);
        rowSound = c.getBoolean("settings.sounds.row.enabled", true);
        rowKey = c.getString("settings.sounds.row.key", "minecraft:block.note_block.bit");
        rowVolume = (float) c.getDouble("settings.sounds.row.volume", 0.45);
        rowPitchStart = (float) c.getDouble("settings.sounds.row.pitch-start", 0.8);
        rowPitchStep = (float) c.getDouble("settings.sounds.row.pitch-step", 0.07);
    }
}
