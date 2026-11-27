package me.mn.leaderboards;

import me.mn.leaderboards.Board.Entry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Consumer;

/**
 * One animated, rotating leaderboard in the world (or a private preview of one).
 *
 * Animation per board:  TYPE (title types in, with sound)  ->  ROWS (rows slide in one by one, springy)
 *                       ->  HOLD (shimmer on #1, progress bar fills)  ->  EXIT (rows slide out, title erases)
 *                       ->  next board in the rotation.
 */
final class BoardDisplay {

    private enum Phase { IDLE, TYPE, ROWS, HOLD, EXIT }

    private static final int ENTER_TICKS = 10;
    private static final int EXIT_TICKS = 8;
    private static final float SLIDE = 1.4f;
    private static final int MIN_ALPHA = 30;
    private static final List<TextColor> GOLD = List.of(
            TextColor.color(0xB8860B), TextColor.color(0xFFD700), TextColor.color(0xFFF2A8),
            TextColor.color(0xFFD700), TextColor.color(0xB8860B));
    private static final String[] SAMPLE_NAMES = {
            "Notch", "Dream", "Technoblade", "Steve", "Alex", "Herobrine", "jeb_", "Dinnerbone", "Grian", "Mumbo",
            "Etho", "Bdubs", "Scar", "Cub", "Impulse", "Zedaph", "Skizzleman", "Keralis", "Xisuma", "Tango"};

    private final MNLeaderboards plugin;
    private final Settings st;
    final String id;
    private Location base;                 // bottom-centre of the panel, yaw = direction the front faces
    private List<String> boardIds;
    private final UUID privateFor;         // null = public display, else preview for that player only
    private final boolean forceSample;     // preview "demo": always fake data
    private final boolean sampleIfEmpty;   // preview: fake data when the real board is empty
    private final long expireTick;         // previews only, -1 = never

    // geometry (blocks)
    private final float panelW, panelH, titleY, dotsY, barY, youY;
    private final float[] rowY;

    // entities
    private BlockDisplay panel;
    private TextDisplay title, dots, bar;
    private final List<TextDisplay> rows = new ArrayList<>();
    private final Map<UUID, TextDisplay> youLines = new HashMap<>();
    private Set<UUID> viewers = new HashSet<>();

    // state
    private Phase phase = Phase.IDLE;
    private int pt;                        // ticks inside the current phase
    private long age;                      // ticks since this object was created
    private int idx;                       // index in rotation
    private Board board;
    private List<Entry> entries = List.of();
    private boolean sampleActive;
    private int rowCount;
    private int lastTyped;
    private int holdTicks;
    private int lastBarFill = -1;
    private int lastIconFrame = -1;

    BoardDisplay(MNLeaderboards plugin, String id, Location base, List<String> boardIds,
                 UUID privateFor, boolean forceSample, boolean sampleIfEmpty, long expireTick) {
        this.plugin = plugin;
        this.st = plugin.settings();
        this.id = id;
        this.base = base.clone();
        this.boardIds = new ArrayList<>(boardIds);
        this.privateFor = privateFor;
        this.forceSample = forceSample;
        this.sampleIfEmpty = sampleIfEmpty;
        this.expireTick = expireTick;

        int n = st.positions;
        float s = st.textScale;
        float lineH = 0.25f * s;
        float spacing = st.rowSpacing * s;
        this.rowY = new float[n];
        float lastRowBottom = 0.52f * s;
        for (int i = 0; i < n; i++) rowY[i] = lastRowBottom + (n - 1 - i) * spacing;
        float firstRowTop = rowY[0] + lineH;
        this.barY = firstRowTop + 0.10f * s;              // gradient underline sits right under the title
        this.dotsY = barY;
        this.titleY = barY + 0.12f * s;
        this.panelH = titleY + lineH * st.titleScale + 0.2f * s;
        this.panelW = (st.rowWidth + 44) * 0.025f * s;
        this.youY = 0.12f * s;
    }

    // ═══════════════════════════ public control ═══════════════════════════

    Location location() { return base.clone(); }

    List<String> boardIds() { return Collections.unmodifiableList(boardIds); }

    void setLocation(Location loc) {
        this.base = loc.clone();
        restart();
    }

    void setBoards(List<String> ids) {
        this.boardIds = new ArrayList<>(ids);
        this.idx = 0;
        restart();
    }

    boolean running() { return phase != Phase.IDLE; }

    String currentBoardId() { return board == null ? null : board.id; }

    /** Jump to the exit animation, then the next board. */
    void skip() {
        if (phase == Phase.TYPE || phase == Phase.ROWS || phase == Phase.HOLD) {
            phase = Phase.EXIT;
            pt = 0;
        }
    }

    /** Remove entities; they respawn on the next tick if players are near. */
    void restart() {
        despawn();
        phase = Phase.IDLE;
    }

    void shutdown() {
        despawn();
        phase = Phase.IDLE;
    }

    boolean expired(long now) { return expireTick >= 0 && now >= expireTick; }

    // ═══════════════════════════ main tick ═══════════════════════════

    void tick() {
        age++;

        if (phase == Phase.IDLE) {
            if (age % 20 == 1) {
                if (!nearby().isEmpty() && spawn()) beginBoard();
            }
            return;
        }

        if (age % 10 == 0) {
            Set<UUID> now = nearby();
            if (now.isEmpty() && privateFor == null) {
                despawn();
                phase = Phase.IDLE;
                return;
            }
            syncViewers(now);
        }
        if (!entitiesValid()) {
            despawn();
            phase = Phase.IDLE;
            return;
        }

        switch (phase) {
            case TYPE -> tickType();
            case ROWS -> tickRows();
            case HOLD -> tickHold();
            case EXIT -> tickExit();
            default -> { }
        }
    }

    // ───────────── TYPE: title is typed in ─────────────
    private void tickType() {
        String t = board.title;
        int typed = Math.min(t.length(), pt / st.typingTicks);
        if (typed != lastTyped) {
            lastTyped = typed;
            if (typed > 0 && typed <= t.length() && pt % st.typingTicks == 0) {
                play(st.typingSound, st.typingKey, st.typingVolume, st.typingPitch + (typed % 3) * 0.05f);
            }
            renderTitle(typed, true);
        }
        pt++;
        // type + a short pause with the cursor
        if (pt / st.typingTicks >= t.length() + 4) {
            renderTitle(t.length(), false);
            phase = Phase.ROWS;
            pt = 0;
        }
    }

    // ───────────── ROWS: rows slide in one after another ─────────────
    private void tickRows() {
        for (int i = 0; i < rowCount; i++) {
            int local = pt - i * st.rowDelay;
            TextDisplay r = rows.get(i);
            if (local < 0) continue;
            if (local == 0) {
                r.text(buildRow(i, 0));
                play(st.rowSound, st.rowKey, st.rowVolume, Math.min(2f, st.rowPitchStart + i * st.rowPitchStep));
            }
            if (local <= ENTER_TICKS) {
                float p = local / (float) ENTER_TICKS;
                float e = Util.outBack(p);
                float a = Util.outCubic(p);
                applyRow(r, i, Util.lerp(-SLIDE, 0f, e), Util.lerp(0.7f, 1f, Math.min(1f, e)), alpha(a));
            }
        }
        if (rowCount == 0 && pt == 0 && !rows.isEmpty()) {
            rows.get(0).text(Component.text("No entries yet", NamedTextColor.DARK_GRAY));
            applyRow(rows.get(0), 0, 0f, 1f, 255);
        }
        pt++;
        int total = Math.max(0, rowCount - 1) * st.rowDelay + ENTER_TICKS + 2;
        if (pt >= total) {
            phase = Phase.HOLD;
            pt = 0;
            holdTicks = board.seconds * 20;
            updateYouLines();
        }
    }

    // ───────────── HOLD: shimmer + progress bar ─────────────
    private void tickHold() {
        pt++;
        if (st.shimmer && rowCount > 0 && pt % 2 == 0) {
            rows.get(0).text(buildRow(0, pt));
        }
        if (board.animatedIcon()) {
            int f = (int) ((age / board.iconFrameTicks) % board.iconFrames);
            if (f != lastIconFrame) {
                lastIconFrame = f;
                renderTitle(board.title.length(), false);
            }
        }
        updateBar(pt / (float) holdTicks);
        if (pt % 20 == 0) updateYouLines();
        if (pt >= holdTicks) {
            phase = Phase.EXIT;
            pt = 0;
        }
    }

    // ───────────── EXIT: rows slide out right, title erased ─────────────
    private void tickExit() {
        for (int i = 0; i < rowCount; i++) {
            int local = pt - i;
            if (local < 0 || local > EXIT_TICKS + 1) continue;
            TextDisplay r = rows.get(i);
            if (local >= EXIT_TICKS) {
                r.text(Component.empty());
                continue;
            }
            float p = local / (float) EXIT_TICKS;
            float e = Util.inCubic(p);
            applyRow(r, i, Util.lerp(0f, SLIDE, e), Util.lerp(1f, 0.8f, e), alpha(1f - e));
        }
        if (rowCount == 0 && pt == 0 && !rows.isEmpty()) rows.get(0).text(Component.empty());

        int typed = Math.max(0, board.title.length() - pt * 2);
        if (typed != lastTyped) {
            lastTyped = typed;
            renderTitle(typed, false);
        }
        updateBar(Math.max(0f, 1f - pt / (float) EXIT_TICKS));
        pt++;
        int end = Math.max(rowCount, 1) + EXIT_TICKS + 2;
        end = Math.max(end, board.title.length() / 2 + 2);
        if (pt >= end) {
            idx = (idx + 1) % Math.max(1, boardIds.size());
            beginBoard();
        }
    }

    // ═══════════════════════════ cycle setup ═══════════════════════════

    private void beginBoard() {
        board = null;
        for (int tries = 0; tries < boardIds.size() && board == null; tries++) {
            Board b = plugin.boards().get(boardIds.get(idx % boardIds.size()));
            if (b != null) board = b;
            else idx++;
        }
        if (board == null) { // nothing valid to show
            despawn();
            phase = Phase.IDLE;
            return;
        }
        idx = idx % boardIds.size();

        // data
        sampleActive = false;
        List<Entry> real = plugin.scores().top(board.id, st.positions);
        if (forceSample || (sampleIfEmpty && real.isEmpty())) {
            entries = sample(board);
            sampleActive = true;
        } else {
            entries = real;
        }
        rowCount = Math.min(entries.size(), rows.size());

        // visuals reset
        if (panel != null && st.panelGlow) {
            panel.setGlowColorOverride(toBukkit(board.colorAt(0.5f)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextDisplay r = rows.get(i);
            r.text(Component.empty());
            applyRow(r, i, -SLIDE, 0.7f, MIN_ALPHA);
        }
        updateDots();
        updateBar(0f);
        lastTyped = -1;
        lastIconFrame = -1;
        renderTitle(0, true);
        phase = Phase.TYPE;
        pt = 0;
    }

    // ═══════════════════════════ rendering ═══════════════════════════

    private void renderTitle(int typed, boolean cursor) {
        if (title == null || board == null) return;
        TextComponent.Builder b = Component.text();
        String icon = board.iconFor(age);
        if (!icon.isEmpty()) {
            try {
                b.append(Util.MM.deserialize(icon));
                b.append(Component.text(" "));
            } catch (Exception ignored) { }
        }
        String shown = board.title.substring(0, Math.min(typed, board.title.length()));
        // gradient is computed over the FULL title so colours don't jump while typing
        Component full = Util.spread(board.title, board.colors, true);
        b.append(sliceChars(full, shown.length(), board.title.length()));
        if (cursor && typed < board.title.length() + 3) {
            b.append(Component.text(age % 10 < 5 ? "▌" : " ", NamedTextColor.WHITE));
        }
        title.text(b.build());
    }

    /** First n characters of a per-character-coloured title (it is built from single-char children). */
    private Component sliceChars(Component spread, int n, int total) {
        TextComponent.Builder b = Component.text();
        List<Component> kids = spread.children();
        for (int i = 0; i < n && i < kids.size(); i++) b.append(kids.get(i));
        return b.build();
    }

    private void updateDots() {
        if (dots == null || board == null) return;
        TextComponent.Builder b = Component.text();
        for (int i = 0; i < boardIds.size(); i++) {
            boolean cur = i == idx;
            Board bd = plugin.boards().get(boardIds.get(i));
            TextColor c = (cur && bd != null) ? bd.colorAt(0.5f) : TextColor.color(0x555555);
            if (i > 0) b.append(Component.text(" "));
            b.append(Component.text("⏺", c));
        }
        dots.text(b.build());
    }

    private void updateBar(float progress) {
        if (bar == null || board == null) return;
        final int segments = Math.max(10, st.rowWidth / 4);
        int fill = Math.round(Util.clamp01(progress) * segments);
        if (fill == lastBarFill) return;
        lastBarFill = fill;
        TextColor on = board.colorAt(0.5f);
        TextComponent.Builder b = Component.text();
        if (fill > 0) {
            b.append(Component.text(" ".repeat(fill)).color(on).decoration(TextDecoration.STRIKETHROUGH, true));
        }
        if (fill < segments) {
            b.append(Component.text(" ".repeat(segments - fill)).color(Util.mix(on, TextColor.color(0x000000), 0.62f))
                    .decoration(TextDecoration.STRIKETHROUGH, true));
        }
        bar.text(b.build());
    }

    /** One leaderboard row, padded in font pixels so every column lines up. */
    private Component buildRow(int i, int shimmerTick) {
        Entry e = entries.get(i);
        int rank = i + 1;
        String rankStr = (st.circled && rank <= 10) ? String.valueOf((char) (0x2460 + rank - 1)) : "#" + rank;
        return rowComponent(rankStr, rankColor(rank), e.uuid(), e.name(), rank == 1 && st.shimmer, shimmerTick,
                board.formatValue(e.value()), NamedTextColor.WHITE);
    }

    /** rank + head + name + dotted filler + value + icon, padded to exactly rowWidth pixels. */
    private Component rowComponent(String rankStr, TextColor rc, UUID headOf, String name, boolean shimmer, int shimmerTick,
                                   String valueStr, TextColor nameColor) {
        TextComponent.Builder b = Component.text();

        // rank column (fixed width so ① .. ⑩ and #75 line up)
        b.append(Component.text(rankStr, rc));
        int used = Util.px(rankStr);
        int rankCol = Util.px("#10") + 4;
        int padSp = Math.max(0, (rankCol - used) / 4);
        if (padSp > 0) b.append(Component.text(" ".repeat(padSp)));
        used += padSp * 4;

        // player head (1.21.9+ object component)
        if (st.heads && headOf != null) {
            b.append(Component.object(ObjectContents.playerHead(headOf)));
            b.append(Component.text(" "));
            used += st.iconWidth + 4;
        }

        // value icon (same sprite as the title)
        Component valueIcon = null;
        int valueW = Util.px(valueStr);
        if (st.valueIcons && !board.icon.isEmpty()) {
            try {
                valueIcon = Util.MM.deserialize(board.iconFor(age));
                valueW += 4 + st.iconWidth;
            } catch (Exception ignored) { }
        }

        // name (truncated so a long name never pushes the value out)
        int maxName = st.rowWidth - used - valueW - 12;
        while (name.length() > 1 && Util.px(name) > maxName) name = name.substring(0, name.length() - 1);
        if (shimmer) {
            float shift = (shimmerTick * 0.025f) % 1.0f;
            b.append(Util.gradient(name, GOLD, shift, false));
        } else {
            b.append(Component.text(name, nameColor));
        }
        used += Util.px(name);

        // dotted filler
        int remaining = st.rowWidth - used - valueW;
        int dotsN = Math.max(0, (remaining - 8) / 2);
        b.append(Component.text(" " + ".".repeat(dotsN) + " ", NamedTextColor.DARK_GRAY));

        b.append(Component.text(valueStr, board.valueColor));
        if (valueIcon != null) {
            b.append(Component.text(" "));
            b.append(valueIcon);
        }
        return b.build();
    }

    private TextColor rankColor(int rank) {
        switch (rank) {
            case 1: return TextColor.color(0xFFD700);
            case 2: return TextColor.color(0xC0C0C0);
            case 3: return TextColor.color(0xCD7F32);
            default: return board.colorAt(Math.min(1f, (rank - 1) / (float) Math.max(1, st.positions)));
        }
    }

    /** The viewer's own position, shown as the last line (only that player sees it). */
    private void updateYouLines() {
        if (!st.youLine || board == null) return;
        for (Map.Entry<UUID, TextDisplay> en : youLines.entrySet()) {
            Player pl = Bukkit.getPlayer(en.getKey());
            String name = pl == null ? "?" : pl.getName();
            String rankStr;
            String valueStr;
            if (sampleActive) {
                rankStr = "#7";
                valueStr = board.formatValue(entries.isEmpty() ? 0 : entries.get(entries.size() - 1).value() * 0.9);
            } else {
                Entry me = plugin.scores().entry(board.id, en.getKey());
                if (me == null) {
                    rankStr = "#-";
                    valueStr = board.formatValue(0);
                } else {
                    rankStr = "#" + plugin.scores().rank(board.id, en.getKey());
                    valueStr = board.formatValue(me.value());
                }
            }
            en.getValue().text(rowComponent(rankStr, board.colorAt(0.5f), null, name, false, 0, valueStr, NamedTextColor.GRAY));
        }
    }

    // ═══════════════════════════ entities ═══════════════════════════

    private boolean spawn() {
        World w = base.getWorld();
        if (w == null) return false;
        if (!w.isChunkLoaded(base.getBlockX() >> 4, base.getBlockZ() >> 4)) return false;

        float s = st.textScale;
        final float range = (float) Math.max(0.1, st.viewDistance / 64.0);

        if (st.panelEnabled) {
            panel = spawnDisplay(BlockDisplay.class, e -> {
                e.setBlock(st.panelMaterial.createBlockData());
                e.setTransformation(new Transformation(
                        new Vector3f(-panelW / 2f, 0f, -0.08f), new Quaternionf(),
                        new Vector3f(panelW, panelH, 0.04f), new Quaternionf()));
                if (st.panelGlow) {
                    e.setGlowing(true);
                    e.setGlowColorOverride(Color.fromRGB(0xA745FF));
                }
            }, range);
        }

        title = spawnText(titleY, 400, range, s * st.titleScale);
        dots = st.dots ? spawnText(dotsY, 200, range, s) : null;
        bar = spawnText(barY, 200, range, s);

        rows.clear();
        for (int i = 0; i < st.positions; i++) {
            TextDisplay r = spawnText(rowY[i], st.rowWidth + 120, range, s);
            applyRow(r, i, -SLIDE, 0.7f, MIN_ALPHA);
            rows.add(r);
        }

        viewers = new HashSet<>();
        syncViewers(nearby());
        return true;
    }

    private TextDisplay spawnText(float y, int lineWidth, float range, float scale) {
        return spawnText(y, lineWidth, range, scale, privateFor == null);
    }

    private TextDisplay spawnText(float y, int lineWidth, float range, float scale, boolean visible) {
        return spawnDisplay(TextDisplay.class, e -> {
            e.setAlignment(TextDisplay.TextAlignment.CENTER);
            e.setLineWidth(lineWidth);
            e.setDefaultBackground(false);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setShadowed(true);
            e.setSeeThrough(false);
            e.text(Component.empty());
            e.setTransformation(new Transformation(
                    new Vector3f(0f, y, 0f), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        }, range, visible);
    }

    private <T extends Display> T spawnDisplay(Class<T> type, Consumer<T> init, float range) {
        return spawnDisplay(type, init, range, privateFor == null);
    }

    private <T extends Display> T spawnDisplay(Class<T> type, Consumer<T> init, float range, boolean visible) {
        Location loc = base.clone();
        loc.setPitch(0f);
        return loc.getWorld().spawn(loc, type, e -> {
            e.setPersistent(false);
            e.setVisibleByDefault(visible);
            e.setBillboard(Display.Billboard.FIXED);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(range);
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(2);
            init.accept(e);
        });
    }

    private void applyRow(TextDisplay r, int i, float tx, float scale, int alpha) {
        float s = scale * st.textScale;
        r.setTransformation(new Transformation(
                new Vector3f(tx * st.textScale, rowY[i], 0f), new Quaternionf(),
                new Vector3f(s, s, s), new Quaternionf()));
        r.setInterpolationDelay(0);
        r.setInterpolationDuration(2);
        r.setTextOpacity((byte) alpha);
    }

    private static int alpha(float a) {
        return MIN_ALPHA + Math.round((255 - MIN_ALPHA) * Util.clamp01(a));
    }

    private boolean entitiesValid() {
        if (title == null || !title.isValid() || (panel != null && !panel.isValid())) return false;
        for (TextDisplay r : rows) if (!r.isValid()) return false;
        return true;
    }

    private void despawn() {
        remove(panel); panel = null;
        remove(title); title = null;
        remove(dots); dots = null;
        remove(bar); bar = null;
        for (TextDisplay r : rows) remove(r);
        rows.clear();
        for (TextDisplay y : youLines.values()) remove(y);
        youLines.clear();
        viewers = new HashSet<>();
        lastBarFill = -1;
    }

    private static void remove(org.bukkit.entity.Entity e) {
        if (e != null && e.isValid()) e.remove();
    }

    // ═══════════════════════════ viewers ═══════════════════════════

    private Set<UUID> nearby() {
        Set<UUID> out = new HashSet<>();
        World w = base.getWorld();
        if (w == null) return out;
        if (privateFor != null) {
            Player p = Bukkit.getPlayer(privateFor);
            if (p != null && p.isOnline() && p.getWorld().equals(w)) out.add(p.getUniqueId());
            return out;
        }
        double r2 = st.viewDistance * st.viewDistance;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(base) <= r2) out.add(p.getUniqueId());
        }
        return out;
    }

    private void syncViewers(Set<UUID> now) {
        for (UUID u : now) {
            if (viewers.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            if (privateFor != null) { // previews: everything is hidden by default, show it to the owner
                if (panel != null) p.showEntity(plugin, panel);
                p.showEntity(plugin, title);
                if (dots != null) p.showEntity(plugin, dots);
                p.showEntity(plugin, bar);
                for (TextDisplay r : rows) p.showEntity(plugin, r);
            }
            if (st.youLine) {
                TextDisplay y = spawnText(youY, 300, (float) Math.max(0.1, st.viewDistance / 64.0), st.textScale, false);
                p.showEntity(plugin, y);
                youLines.put(u, y);
                if (phase == Phase.HOLD) updateYouLines();
            }
        }
        for (UUID u : new ArrayList<>(viewers)) {
            if (now.contains(u)) continue;
            TextDisplay y = youLines.remove(u);
            if (y != null) remove(y);
        }
        viewers = now;
    }

    private void play(boolean enabled, String key, float volume, float pitch) {
        if (!enabled || volume <= 0f) return;
        for (UUID u : viewers) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.playSound(base, key, SoundCategory.RECORDS, volume, pitch);
        }
    }

    // ═══════════════════════════ helpers ═══════════════════════════

    private static Color toBukkit(TextColor c) {
        return Color.fromRGB(c.red(), c.green(), c.blue());
    }

    /** Fake but stable data for previews of boards that have no real entries yet. */
    private List<Entry> sample(Board b) {
        Random rnd = new Random(b.id.hashCode());
        double top = switch (b.format) {
            case "time" -> 20 * 3600 * (400 + rnd.nextInt(300));
            case "seconds" -> 3600 * (400 + rnd.nextInt(300));
            case "distance" -> 100_000.0 * (900 + rnd.nextInt(400));
            default -> 400 + rnd.nextInt(2000);
        };
        List<Entry> out = new ArrayList<>();
        double v = top;
        for (int i = 0; i < Math.min(st.positions, SAMPLE_NAMES.length); i++) {
            String n = SAMPLE_NAMES[i];
            out.add(new Entry(UUID.nameUUIDFromBytes(("OfflinePlayer:" + n).getBytes()), n, Math.max(1, Math.round(v))));
            v *= 0.80 + rnd.nextDouble() * 0.15;
        }
        if (board != null && board.ascending) {
            Collections.reverse(out);
        }
        return out;
    }
}
