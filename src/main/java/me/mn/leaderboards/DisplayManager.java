package me.mn.leaderboards;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Owns every placed display + running previews, and drives them with a single 1-tick task. */
final class DisplayManager {

    private final MNLeaderboards plugin;
    private final File file;
    private final Map<String, BoardDisplay> displays = new LinkedHashMap<>();
    private final Map<UUID, BoardDisplay> previews = new HashMap<>();
    private BukkitTask task;
    private long tick;

    DisplayManager(MNLeaderboards plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "displays.yml");
    }

    // ───────────── persistence ─────────────

    void load() {
        displays.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("displays");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) {
                plugin.getLogger().warning("Display '" + id + "': world '" + s.getString("world") + "' is not loaded - skipped.");
                continue;
            }
            Location loc = new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), (float) s.getDouble("yaw"), 0f);
            displays.put(id, new BoardDisplay(plugin, id, loc, s.getStringList("boards"), null, false, false, -1));
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (BoardDisplay d : displays.values()) {
            Location l = d.location();
            String p = "displays." + d.id + ".";
            y.set(p + "world", l.getWorld() == null ? "world" : l.getWorld().getName());
            y.set(p + "x", l.getX());
            y.set(p + "y", l.getY());
            y.set(p + "z", l.getZ());
            y.set(p + "yaw", (double) l.getYaw());
            y.set(p + "boards", d.boardIds());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save displays.yml: " + e.getMessage());
        }
    }

    // ───────────── lifecycle ─────────────

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 20L, 1L);
    }

    void shutdown() {
        if (task != null) { task.cancel(); task = null; }
        for (BoardDisplay d : displays.values()) d.shutdown();
        for (BoardDisplay d : previews.values()) d.shutdown();
        displays.clear();
        previews.clear();
    }

    private void tickAll() {
        tick++;
        for (BoardDisplay d : displays.values()) d.tick();
        if (!previews.isEmpty()) {
            Iterator<Map.Entry<UUID, BoardDisplay>> it = previews.entrySet().iterator();
            while (it.hasNext()) {
                BoardDisplay d = it.next().getValue();
                if (d.expired(tick)) {
                    d.shutdown();
                    it.remove();
                } else {
                    d.tick();
                }
            }
        }
    }

    // ───────────── displays ─────────────

    Collection<BoardDisplay> all() { return displays.values(); }

    BoardDisplay get(String id) { return displays.get(id); }

    boolean create(String id, Location loc, List<String> boards) {
        if (displays.containsKey(id)) return false;
        displays.put(id, new BoardDisplay(plugin, id, loc, boards, null, false, false, -1));
        save();
        return true;
    }

    boolean delete(String id) {
        BoardDisplay d = displays.remove(id);
        if (d == null) return false;
        d.shutdown();
        save();
        return true;
    }

    // ───────────── previews ─────────────

    /** Private preview only the given player can see. Replaces their previous one. */
    void startPreview(Player p, Location loc, List<String> boards, boolean forceSample, int seconds) {
        stopPreview(p.getUniqueId());
        BoardDisplay d = new BoardDisplay(plugin, "preview-" + p.getName(), loc, boards, p.getUniqueId(),
                forceSample, true, tick + seconds * 20L);
        previews.put(p.getUniqueId(), d);
    }

    boolean stopPreview(UUID player) {
        BoardDisplay d = previews.remove(player);
        if (d == null) return false;
        d.shutdown();
        return true;
    }

    BoardDisplay preview(UUID player) { return previews.get(player); }
}
