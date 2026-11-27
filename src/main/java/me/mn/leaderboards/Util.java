package me.mn.leaderboards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Locale;

/** Small helpers: pixel widths, easing, number formatting, gradients. */
final class Util {
    private Util() {}

    static final MiniMessage MM = MiniMessage.miniMessage();

    // ───────────── pixel widths of the default Minecraft font (advance incl. 1px gap) ─────────────
    static int px(char c) {
        switch (c) {
            case ' ': return 4;
            case '!': case ',': case '.': case ':': case ';': case 'i': case '|': return 2;
            case '\'': case 'l': case '`': return 3;
            case 'I': case 't': case '[': case ']': return 4;
            case '"': case '(': case ')': case '*': case '<': case '>': case 'f': case 'k': case '{': case '}': return 5;
            case '@': case '~': return 7;
            default:
                if (c >= 0x2460 && c <= 0x2473) return 9; // circled numbers
                return c < 128 ? 6 : 8;
        }
    }

    static int px(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += px(s.charAt(i));
        return w;
    }

    // ───────────── easing ─────────────
    static float clamp01(float t) { return t < 0 ? 0 : (t > 1 ? 1 : t); }

    static float outCubic(float t) { t = clamp01(t); float u = 1 - t; return 1 - u * u * u; }

    static float inCubic(float t) { t = clamp01(t); return t * t * t; }

    /** Slight overshoot, gives the slide-in a springy feel. */
    static float outBack(float t) {
        t = clamp01(t);
        final float c1 = 1.70158f, c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    // ───────────── colours ─────────────
    static TextColor mix(TextColor a, TextColor b, float t) {
        t = clamp01(t);
        int r = Math.round(lerp(a.red(), b.red(), t));
        int g = Math.round(lerp(a.green(), b.green(), t));
        int bl = Math.round(lerp(a.blue(), b.blue(), t));
        return TextColor.color(r, g, bl);
    }

    /** Sample a multi-stop gradient at t in [0,1]. */
    static TextColor sample(List<TextColor> cols, float t) {
        if (cols.size() == 1) return cols.get(0);
        t = clamp01(t) * (cols.size() - 1);
        int i = Math.min((int) t, cols.size() - 2);
        return mix(cols.get(i), cols.get(i + 1), t - i);
    }

    /** Per-character gradient. {@code shift} slides the gradient (used for the shimmer). */
    static Component gradient(String s, List<TextColor> cols, float shift, boolean bold) {
        TextComponent.Builder b = Component.text();
        int n = Math.max(1, s.length() - 1);
        for (int i = 0; i < s.length(); i++) {
            float t = (i / (float) n + shift) % 1.0f;
            if (t < 0) t += 1;
            // mirror so a wrapped gradient never jumps
            float m = t < 0.5f ? t * 2 : (1 - t) * 2;
            Component ch = Component.text(String.valueOf(s.charAt(i)), sample(cols, cols.size() == 1 ? 0 : m));
            if (bold) ch = ch.decoration(TextDecoration.BOLD, true);
            b.append(ch);
        }
        return b.build();
    }

    /** Gradient that spans the colours once, left to right (no mirroring). */
    static Component spread(String s, List<TextColor> cols, boolean bold) {
        TextComponent.Builder b = Component.text();
        int n = Math.max(1, s.length() - 1);
        for (int i = 0; i < s.length(); i++) {
            Component ch = Component.text(String.valueOf(s.charAt(i)), sample(cols, i / (float) n));
            if (bold) ch = ch.decoration(TextDecoration.BOLD, true);
            b.append(ch);
        }
        return b.build();
    }

    // ───────────── number formatting ─────────────
    static String compact(double v) {
        double a = Math.abs(v);
        if (a < 1000) return trim(v, 0);
        if (a < 1_000_000) return trim(v / 1_000, 1) + "K";
        if (a < 1_000_000_000) return trim(v / 1_000_000, 1) + "M";
        if (a < 1_000_000_000_000L) return trim(v / 1_000_000_000, 1) + "B";
        return trim(v / 1_000_000_000_000L, 1) + "T";
    }

    private static String trim(double v, int dec) {
        String s = String.format(Locale.US, "%." + dec + "f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    static String full(double v) {
        return String.format(Locale.US, "%,d", Math.round(v));
    }

    static String duration(long totalSeconds) {
        long d = totalSeconds / 86400, h = (totalSeconds % 86400) / 3600, m = (totalSeconds % 3600) / 60, s = totalSeconds % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    static String distance(double cm) {
        double blocks = cm / 100.0;
        if (blocks < 1000) return trim(blocks, 0) + "m";
        return trim(blocks / 1000.0, 1) + "km";
    }
}
