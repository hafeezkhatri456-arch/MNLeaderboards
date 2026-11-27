package me.mn.leaderboards;

import me.mn.leaderboards.Board.Entry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

/** Reads scores in small batches (no lag) and keeps sorted top lists + ranks per board. */
final class ScoreService {

    private final MNLeaderboards plugin;
    private final File dataFile;
    private YamlConfiguration data;

    private Map<String, List<Entry>> sorted = new HashMap<>();
    private Map<String, Map<UUID, Integer>> ranks = new HashMap<>();
    private BukkitTask running;
    private Method papi;
    /** Last known placeholder values, so offline players still rank when an expansion can't read them offline. */
    private final Map<String, Map<UUID, Double>> cache = new HashMap<>();

    ScoreService(MNLeaderboards plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.data = YamlConfiguration.loadConfiguration(dataFile);
        loadCache();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                        .getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            } catch (Throwable t) {
                plugin.getLogger().warning("PlaceholderAPI found but could not be hooked: " + t.getMessage());
            }
        }
    }

    void stop() {
        if (running != null) { running.cancel(); running = null; }
    }

    // ───────────── reading ─────────────

    List<Entry> top(String boardId, int n) {
        List<Entry> l = sorted.get(boardId);
        if (l == null) return List.of();
        return l.subList(0, Math.min(n, l.size()));
    }

    /** 1-based rank or -1. */
    int rank(String boardId, UUID uuid) {
        Map<UUID, Integer> m = ranks.get(boardId);
        Integer r = m == null ? null : m.get(uuid);
        return r == null ? -1 : r;
    }

    Entry entry(String boardId, UUID uuid) {
        int r = rank(boardId, uuid);
        if (r < 1) return null;
        return sorted.get(boardId).get(r - 1);
    }

    boolean hasPlaceholderApi() { return papi != null; }

    // ───────────── refreshing ─────────────

    void refresh() {
        if (running != null) return; // previous refresh still going

        Map<String, List<Entry>> out = new HashMap<>();
        List<Board> scan = new ArrayList<>();
        for (Board b : plugin.boards().values()) {
            if (b.type == Board.Type.CUSTOM) {
                out.put(b.id, loadCustom(b));
            } else {
                out.put(b.id, new ArrayList<>());
                scan.add(b);
            }
        }
        if (scan.isEmpty()) { commit(out); return; }

        final OfflinePlayer[] all = Bukkit.getOfflinePlayers();
        final int days = plugin.settings().inactiveDays;
        final long cutoff = days > 0 ? System.currentTimeMillis() - days * 86_400_000L : 0L;
        final int batch = plugin.settings().batchSize;

        running = new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                int n = 0;
                while (i < all.length && n < batch) {
                    OfflinePlayer op = all[i++];
                    n++;
                    String name = op.getName();
                    if (name == null) continue;
                    if (cutoff > 0 && !op.isOnline() && op.getLastPlayed() < cutoff) continue;
                    for (Board b : scan) {
                        Double v = read(b, op);
                        if (v == null || v < b.minimum) continue;
                        out.get(b.id).add(new Entry(op.getUniqueId(), name, v));
                    }
                }
                if (i >= all.length) {
                    cancel();
                    running = null;
                    commit(out);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private Double read(Board b, OfflinePlayer op) {
        try {
            if (b.type == Board.Type.STATISTIC) {
                return (double) op.getStatistic(b.statistic);
            }
            if (b.type == Board.Type.PLACEHOLDER) {
                Double live = readPlaceholder(b, op);
                Map<UUID, Double> c = cache.computeIfAbsent(b.id, k -> new HashMap<>());
                if (live != null) {
                    c.put(op.getUniqueId(), live);
                    return live;
                }
                return c.get(op.getUniqueId()); // offline and unreadable: last known value
            }
        } catch (Throwable ignored) {
            // offline player without stats file, bad placeholder, ... just skip them
        }
        return null;
    }

    private Double readPlaceholder(Board b, OfflinePlayer op) {
        if (papi == null) return null;
        try {
            String r = (String) papi.invoke(null, op, b.placeholder);
            if (r == null) return null;
            r = r.replaceAll("[^0-9.\\-]", "");
            if (r.isEmpty() || r.equals("-") || r.equals(".")) return null;
            return Double.parseDouble(r);
        } catch (Throwable t) {
            return null;
        }
    }

    private void loadCache() {
        File f = new File(plugin.getDataFolder(), "cache.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection sec = y.getConfigurationSection("values");
        if (sec == null) return;
        for (String boardId : sec.getKeys(false)) {
            ConfigurationSection bs = sec.getConfigurationSection(boardId);
            if (bs == null) continue;
            Map<UUID, Double> m = new HashMap<>();
            for (String u : bs.getKeys(false)) {
                try { m.put(UUID.fromString(u), bs.getDouble(u)); } catch (IllegalArgumentException ignored) { }
            }
            cache.put(boardId, m);
        }
    }

    private void saveCacheAsync() {
        final YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Map<UUID, Double>> e : cache.entrySet()) {
            for (Map.Entry<UUID, Double> v : e.getValue().entrySet()) {
                y.set("values." + e.getKey() + "." + v.getKey(), v.getValue());
            }
        }
        final File f = new File(plugin.getDataFolder(), "cache.yml");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { y.save(f); } catch (IOException ex) { plugin.getLogger().warning("Could not save cache.yml: " + ex.getMessage()); }
        });
    }

    private void commit(Map<String, List<Entry>> out) {
        Map<String, Map<UUID, Integer>> newRanks = new HashMap<>();
        for (Board b : plugin.boards().values()) {
            List<Entry> list = out.getOrDefault(b.id, new ArrayList<>());
            Comparator<Entry> cmp = Comparator.comparingDouble(Entry::value);
            if (!b.ascending) cmp = cmp.reversed();
            cmp = cmp.thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
            list.sort(cmp);
            Map<UUID, Integer> r = new HashMap<>();
            for (int i = 0; i < list.size(); i++) r.put(list.get(i).uuid(), i + 1);
            newRanks.put(b.id, r);
            out.put(b.id, list);
        }
        this.sorted = out;
        this.ranks = newRanks;
        if (!cache.isEmpty()) saveCacheAsync();
    }

    // ───────────── custom boards (data.yml) ─────────────

    private List<Entry> loadCustom(Board b) {
        List<Entry> list = new ArrayList<>();
        ConfigurationSection sec = data.getConfigurationSection("custom." + b.id);
        if (sec == null) return list;
        for (String key : sec.getKeys(false)) {
            try {
                UUID u = UUID.fromString(key);
                String name = sec.getString(key + ".name", "?");
                double v = sec.getDouble(key + ".value");
                if (v >= b.minimum) list.add(new Entry(u, name, v));
            } catch (IllegalArgumentException ignored) { }
        }
        return list;
    }

    double customValue(String boardId, UUID u) {
        return data.getDouble("custom." + boardId + "." + u + ".value", 0);
    }

    void setCustom(String boardId, UUID u, String name, double value) {
        data.set("custom." + boardId + "." + u + ".name", name);
        data.set("custom." + boardId + "." + u + ".value", value);
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save data.yml: " + e.getMessage());
        }
        refresh();
    }
}
