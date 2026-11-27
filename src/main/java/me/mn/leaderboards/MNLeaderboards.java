package me.mn.leaderboards;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MNLeaderboards extends JavaPlugin {

    private Settings settings;
    private final Map<String, Board> boards = new LinkedHashMap<>();
    private ScoreService scores;
    private DisplayManager displays;
    private BukkitTask refreshTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAll();

        MnlbCommand cmd = new MnlbCommand(this);
        if (getCommand("mnlb") != null) {
            getCommand("mnlb").setExecutor(cmd);
            getCommand("mnlb").setTabCompleter(cmd);
        }
        getLogger().info("MNLeaderboards enabled - " + boards.size() + " boards, "
                + displays.all().size() + " displays. Use /mnlb help");
    }

    @Override
    public void onDisable() {
        if (refreshTask != null) refreshTask.cancel();
        if (displays != null) displays.shutdown();
        if (scores != null) scores.stop();
    }

    /** (Re)loads config, boards, displays. Used on enable and /mnlb reload. */
    void loadAll() {
        if (refreshTask != null) refreshTask.cancel();
        if (displays != null) displays.shutdown();
        if (scores != null) scores.stop();

        reloadConfig();
        settings = new Settings(getConfig());

        boards.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("boards");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(id);
                if (s == null) continue;
                Board b = Board.load(id, s, getLogger());
                if (b != null) boards.put(id, b);
            }
        }

        scores = new ScoreService(this);
        scores.refresh();
        long period = settings.refreshSeconds * 20L;
        refreshTask = getServer().getScheduler().runTaskTimer(this, () -> scores.refresh(), period, period);

        displays = new DisplayManager(this);
        displays.load();
        displays.start();
    }

    Settings settings() { return settings; }

    Map<String, Board> boards() { return boards; }

    ScoreService scores() { return scores; }

    DisplayManager displays() { return displays; }
}
