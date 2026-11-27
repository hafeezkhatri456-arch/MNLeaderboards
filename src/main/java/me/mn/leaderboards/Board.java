package me.mn.leaderboards;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/** One leaderboard definition (kills, playtime, ...) loaded from config.yml. */
final class Board {

    enum Type { STATISTIC, CUSTOM, PLACEHOLDER }

    record Entry(UUID uuid, String name, double value) {}

    final String id;
    final Type type;
    final Statistic statistic;
    final String placeholder;
    final String title;
    final String icon;
    final int iconFrames;
    final int iconFramePad;
    final int iconFrameTicks;
    final List<TextColor> colors;
    final TextColor valueColor;
    final String format;
    final boolean ascending;
    final double minimum;
    final int seconds;

    private Board(String id, Type type, Statistic statistic, String placeholder, String title, String icon,
                  int iconFrames, int iconFramePad, int iconFrameTicks, List<TextColor> colors,
                  TextColor valueColor, String format, boolean ascending, double minimum, int seconds) {
        this.id = id;
        this.type = type;
        this.statistic = statistic;
        this.placeholder = placeholder;
        this.title = title;
        this.icon = icon;
        this.iconFrames = iconFrames;
        this.iconFramePad = iconFramePad;
        this.iconFrameTicks = Math.max(1, iconFrameTicks);
        this.colors = colors;
        this.valueColor = valueColor;
        this.format = format;
        this.ascending = ascending;
        this.minimum = minimum;
        this.seconds = Math.max(3, seconds);
    }

    static Board load(String id, ConfigurationSection s, Logger log) {
        String src = s.getString("source", "statistic").toLowerCase(Locale.ROOT);
        Type type;
        switch (src) {
            case "custom": type = Type.CUSTOM; break;
            case "placeholder": type = Type.PLACEHOLDER; break;
            default: type = Type.STATISTIC; break;
        }

        Statistic stat = null;
        if (type == Type.STATISTIC) {
            String name = s.getString("statistic", "").toUpperCase(Locale.ROOT).replace(' ', '_');
            if (name.equals("PLAY_TIME")) name = "PLAY_ONE_MINUTE"; // alias used by other plugins
            try {
                stat = Statistic.valueOf(name);
            } catch (IllegalArgumentException ex) {
                log.warning("Board '" + id + "': unknown statistic '" + name + "' - skipped.");
                return null;
            }
            if (stat.getType() != Statistic.Type.UNTYPED) {
                log.warning("Board '" + id + "': statistic " + name + " needs a block/item/entity and is not supported - skipped.");
                return null;
            }
        }
        String ph = s.getString("placeholder", "");
        if (type == Type.PLACEHOLDER && ph.isEmpty()) {
            log.warning("Board '" + id + "': source placeholder needs a 'placeholder:' value - skipped.");
            return null;
        }

        List<TextColor> colors = new ArrayList<>();
        List<String> list = s.getStringList("colors");
        if (list.isEmpty() && s.isString("color")) list = List.of(s.getString("color"));
        for (String c : list) {
            TextColor tc = TextColor.fromHexString(c.startsWith("#") ? c : "#" + c);
            if (tc != null) colors.add(tc);
        }
        if (colors.isEmpty()) colors.add(TextColor.color(0xA745FF));

        TextColor vc = TextColor.fromHexString(s.getString("value-color", "#FFD263"));
        if (vc == null) vc = TextColor.color(0xFFD263);

        return new Board(
                id, type, stat, ph,
                s.getString("title", id.toUpperCase(Locale.ROOT)),
                s.getString("icon", ""),
                s.getInt("icon-frames", 0),
                s.getInt("icon-frame-pad", 0),
                s.getInt("icon-frame-ticks", 3),
                colors, vc,
                s.getString("format", "number").toLowerCase(Locale.ROOT),
                s.getString("sorting", "descending").equalsIgnoreCase("ascending"),
                s.getDouble("minimum-value", 1),
                s.getInt("duration-seconds", 12));
    }

    TextColor colorAt(float t) { return Util.sample(colors, t); }

    /** Icon string for the given animation tick (%frame% replaced for animated icons). */
    String iconFor(long tick) {
        if (icon.isEmpty()) return "";
        if (iconFrames > 0 && icon.contains("%frame%")) {
            int f = (int) ((tick / iconFrameTicks) % iconFrames);
            String num = String.valueOf(f);
            while (num.length() < iconFramePad) num = "0" + num;
            return icon.replace("%frame%", num);
        }
        return icon;
    }

    boolean animatedIcon() { return iconFrames > 0 && icon.contains("%frame%"); }

    String formatValue(double v) {
        switch (format) {
            case "full": return Util.full(v);
            case "time": return Util.duration((long) (v / 20.0));
            case "seconds": return Util.duration((long) v);
            case "distance": return Util.distance(v);
            default: return Util.compact(v);
        }
    }
}
