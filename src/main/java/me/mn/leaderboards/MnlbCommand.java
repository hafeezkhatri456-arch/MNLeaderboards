package me.mn.leaderboards;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Collectors;

/** /mnlb (alias /lb) */
final class MnlbCommand implements TabExecutor {

    private static final String PREFIX = "<gradient:#7c3aed:#22d3ee><bold>MNLB</bold></gradient> <dark_gray>»</dark_gray> <gray>";
    private static final List<String> SUBS = List.of(
            "help", "create", "delete", "list", "boards", "move", "flip", "setboards",
            "start", "stop", "skip", "preview", "reload", "refresh", "set", "add", "tp");

    private final MNLeaderboards plugin;

    MnlbCommand(MNLeaderboards plugin) { this.plugin = plugin; }

    // ───────────── messaging ─────────────

    private void msg(CommandSender s, String mm) {
        s.sendMessage(Util.MM.deserialize(PREFIX + mm));
    }

    private void err(CommandSender s, String mm) {
        s.sendMessage(Util.MM.deserialize(PREFIX + "<red>" + mm));
    }

    private static String esc(String s) { return s.replace("<", "\\<"); }

    // ───────────── command ─────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!sender.hasPermission("mnlb.admin")) {
            err(sender, "You don't have permission to do that.");
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, a);
            case "delete" -> delete(sender, a);
            case "list" -> list(sender);
            case "boards" -> boards(sender);
            case "move" -> move(sender, a);
            case "flip" -> flip(sender, a);
            case "setboards" -> setBoards(sender, a);
            case "start" -> startStop(sender, a, true);
            case "stop" -> startStop(sender, a, false);
            case "skip" -> skip(sender, a);
            case "preview" -> preview(sender, a);
            case "tp" -> tp(sender, a);
            case "reload" -> {
                long t = System.currentTimeMillis();
                plugin.loadAll();
                msg(sender, "Reloaded in <white>" + (System.currentTimeMillis() - t) + "ms</white>.");
            }
            case "refresh" -> {
                plugin.scores().refresh();
                msg(sender, "Refreshing scores in the background...");
            }
            case "set" -> setValue(sender, a, false);
            case "add" -> setValue(sender, a, true);
            default -> err(sender, "Unknown subcommand. Try <white>/" + label + " help</white>.");
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        String l = "/" + label;
        msg(s, "<white>Commands</white> <dark_gray>(" + l + " or /lb)");
        String[][] rows = {
                {"create <id> [board,board...]", "place a display in front of you"},
                {"delete <id>", "remove a display"},
                {"list / boards", "show displays / available boards"},
                {"move <id>", "move a display in front of you"},
                {"flip <id>", "turn it around 180°"},
                {"setboards <id> <board...>", "change which boards rotate"},
                {"start|stop|skip <id>", "respawn / remove / jump to next board"},
                {"preview [board|all] [demo]", "private preview, only you see it"},
                {"preview stop", "end your preview"},
                {"set|add <board> <player> <n>", "values for custom boards"},
                {"refresh | reload | tp <id>", "re-read scores / reload config / teleport"}};
        for (String[] r : rows) {
            s.sendMessage(Util.MM.deserialize("  <#22d3ee>" + esc(l) + " " + esc(r[0]) + "</#22d3ee> <dark_gray>-</dark_gray> <gray>" + esc(r[1])));
        }
    }

    // ───────────── displays ─────────────

    /** Spot 2.5 blocks in front of the player, board front turned towards them. */
    private Location spot(Player p) {
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(2.5);
        Location loc = p.getLocation().clone().add(dir);
        loc.setY(Math.floor(p.getLocation().getY()));
        float yaw = p.getLocation().getYaw() + 180f;
        while (yaw >= 180f) yaw -= 360f;
        while (yaw < -180f) yaw += 360f;
        loc.setYaw(yaw);
        loc.setPitch(0f);
        return loc;
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        err(s, "Only players can use this.");
        return null;
    }

    private List<String> parseBoards(String[] a, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < a.length; i++) {
            for (String part : a[i].split(",")) {
                if (!part.isBlank()) out.add(part.trim());
            }
        }
        if (out.size() == 1 && out.get(0).equalsIgnoreCase("all")) out.clear();
        if (out.isEmpty()) out.addAll(plugin.boards().keySet());
        return out;
    }

    private String unknownBoard(List<String> ids) {
        for (String id : ids) if (!plugin.boards().containsKey(id)) return id;
        return null;
    }

    private void create(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        if (a.length < 2) { err(s, "Usage: /mnlb create <id> [board,board...]"); return; }
        String id = a[1].toLowerCase(Locale.ROOT);
        List<String> boards = parseBoards(a, 2);
        String bad = unknownBoard(boards);
        if (bad != null) { err(s, "No board named <white>" + esc(bad) + "</white>. See <white>/mnlb boards</white>."); return; }
        if (plugin.boards().isEmpty()) { err(s, "No boards configured in config.yml."); return; }
        if (!plugin.displays().create(id, spot(p), boards)) { err(s, "A display named <white>" + esc(id) + "</white> already exists."); return; }
        msg(s, "Created display <white>" + esc(id) + "</white> with " + boards.size() + " board(s). If the text looks mirrored use <white>/mnlb flip " + esc(id) + "</white>.");
    }

    private BoardDisplay need(CommandSender s, String[] a) {
        if (a.length < 2) { err(s, "Usage: /mnlb " + a[0] + " <id>"); return null; }
        BoardDisplay d = plugin.displays().get(a[1].toLowerCase(Locale.ROOT));
        if (d == null) err(s, "No display named <white>" + esc(a[1]) + "</white>.");
        return d;
    }

    private void delete(CommandSender s, String[] a) {
        BoardDisplay d = need(s, a);
        if (d == null) return;
        plugin.displays().delete(d.id);
        msg(s, "Deleted <white>" + esc(d.id) + "</white>.");
    }

    private void list(CommandSender s) {
        Collection<BoardDisplay> all = plugin.displays().all();
        if (all.isEmpty()) { msg(s, "No displays yet. Use <white>/mnlb create <id></white>."); return; }
        msg(s, "Displays (<white>" + all.size() + "</white>):");
        for (BoardDisplay d : all) {
            Location l = d.location();
            s.sendMessage(Util.MM.deserialize("  <dark_gray>-</dark_gray> <white>" + esc(d.id) + "</white> <gray>@ "
                    + (l.getWorld() == null ? "?" : l.getWorld().getName()) + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                    + " <dark_gray>[" + (d.running() ? "<green>running" : "<yellow>idle") + "<dark_gray>] <gray>" + esc(String.join(",", d.boardIds()))));
        }
    }

    private void boards(CommandSender s) {
        msg(s, "Boards (<white>" + plugin.boards().size() + "</white>):");
        for (Board b : plugin.boards().values()) {
            s.sendMessage(Util.MM.deserialize("  <dark_gray>-</dark_gray> <white>" + esc(b.id) + "</white> <gray>" + b.type.name().toLowerCase(Locale.ROOT)
                    + (b.type == Board.Type.STATISTIC ? " " + b.statistic.name() : "") + ", " + b.format + ", " + b.seconds + "s"));
        }
    }

    private void move(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        BoardDisplay d = need(s, a);
        if (d == null) return;
        d.setLocation(spot(p));
        plugin.displays().save();
        msg(s, "Moved <white>" + esc(d.id) + "</white> in front of you.");
    }

    private void flip(CommandSender s, String[] a) {
        BoardDisplay d = need(s, a);
        if (d == null) return;
        Location l = d.location();
        float yaw = l.getYaw() + 180f;
        while (yaw >= 180f) yaw -= 360f;
        l.setYaw(yaw);
        d.setLocation(l);
        plugin.displays().save();
        msg(s, "Flipped <white>" + esc(d.id) + "</white>.");
    }

    private void setBoards(CommandSender s, String[] a) {
        BoardDisplay d = need(s, a);
        if (d == null) return;
        List<String> boards = parseBoards(a, 2);
        String bad = unknownBoard(boards);
        if (bad != null) { err(s, "No board named <white>" + esc(bad) + "</white>."); return; }
        d.setBoards(boards);
        plugin.displays().save();
        msg(s, "<white>" + esc(d.id) + "</white> now rotates: <white>" + esc(String.join(", ", boards)));
    }

    private void startStop(CommandSender s, String[] a, boolean start) {
        BoardDisplay d = need(s, a);
        if (d == null) return;
        d.restart();
        if (start) msg(s, "Restarted <white>" + esc(d.id) + "</white> (it spawns when a player is nearby).");
        else msg(s, "Removed the entities of <white>" + esc(d.id) + "</white> (they come back when a player is nearby; use delete to remove it for good).");
    }

    private void skip(CommandSender s, String[] a) {
        BoardDisplay d = need(s, a);
        if (d == null) return;
        if (!d.running()) { err(s, "<white>" + esc(d.id) + "</white> is idle (no players nearby)."); return; }
        d.skip();
        msg(s, "Skipping to the next board...");
    }

    private void tp(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        BoardDisplay d = need(s, a);
        if (d == null) return;
        Location l = d.location();
        Location to = l.clone();
        Vector front = new Vector(-Math.sin(Math.toRadians(l.getYaw())), 0, Math.cos(Math.toRadians(l.getYaw()))).multiply(3);
        to.add(front);
        to.setYaw(l.getYaw() + 180f);
        to.setPitch(10f);
        p.teleportAsync(to);
        msg(s, "Teleported to <white>" + esc(d.id) + "</white>.");
    }

    // ───────────── preview ─────────────

    private void preview(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        if (a.length >= 2 && a[1].equalsIgnoreCase("stop")) {
            if (plugin.displays().stopPreview(p.getUniqueId())) msg(s, "Preview ended.");
            else err(s, "You have no preview running.");
            return;
        }
        boolean demo = false;
        List<String> ids = new ArrayList<>();
        for (int i = 1; i < a.length; i++) {
            if (a[i].equalsIgnoreCase("demo")) demo = true;
            else ids.addAll(Arrays.asList(a[i].split(",")));
        }
        if (ids.isEmpty() || (ids.size() == 1 && ids.get(0).equalsIgnoreCase("all"))) {
            ids = new ArrayList<>(plugin.boards().keySet());
        }
        String bad = unknownBoard(ids);
        if (bad != null) { err(s, "No board named <white>" + esc(bad) + "</white>."); return; }
        if (ids.isEmpty()) { err(s, "No boards configured."); return; }

        int seconds = Math.max(20, ids.size() * (plugin.boards().get(ids.get(0)).seconds + 6) + 10);
        plugin.displays().startPreview(p, spot(p), ids, demo, Math.min(seconds, 180));
        msg(s, "Preview started <dark_gray>(only you can see it, " + (demo ? "sample data" : "real data, sample if empty") + ")</dark_gray>. "
                + "End it with <white>/mnlb preview stop</white>.");
    }

    // ───────────── custom values ─────────────

    private void setValue(CommandSender s, String[] a, boolean add) {
        if (a.length < 4) { err(s, "Usage: /mnlb " + a[0] + " <board> <player> <number>"); return; }
        Board b = plugin.boards().get(a[1]);
        if (b == null || b.type != Board.Type.CUSTOM) { err(s, "<white>" + esc(a[1]) + "</white> is not a custom board."); return; }
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(a[2]);
        if (op == null) {
            Player online = Bukkit.getPlayerExact(a[2]);
            op = online;
        }
        if (op == null) { err(s, "Player <white>" + esc(a[2]) + "</white> has never joined."); return; }
        double v;
        try {
            v = Double.parseDouble(a[3]);
        } catch (NumberFormatException e) {
            err(s, "<white>" + esc(a[3]) + "</white> is not a number.");
            return;
        }
        String name = op.getName() == null ? a[2] : op.getName();
        double result = add ? plugin.scores().customValue(b.id, op.getUniqueId()) + v : v;
        plugin.scores().setCustom(b.id, op.getUniqueId(), name, result);
        msg(s, "<white>" + esc(name) + "</white> is now at <white>" + b.formatValue(result) + "</white> on <white>" + esc(b.id) + "</white>.");
    }

    // ───────────── tab completion ─────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] a) {
        if (!sender.hasPermission("mnlb.admin")) return List.of();
        String cur = a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length == 1) return filter(SUBS, cur);

        String sub = a[0].toLowerCase(Locale.ROOT);
        List<String> boardIds = new ArrayList<>(plugin.boards().keySet());
        List<String> displayIds = plugin.displays().all().stream().map(d -> d.id).collect(Collectors.toList());

        switch (sub) {
            case "delete", "move", "flip", "start", "stop", "skip", "tp":
                return a.length == 2 ? filter(displayIds, cur) : List.of();
            case "setboards":
                if (a.length == 2) return filter(displayIds, cur);
                return filter(withAll(boardIds), cur);
            case "create":
                return a.length >= 3 ? filter(withAll(boardIds), cur) : List.of();
            case "preview": {
                List<String> opts = withAll(boardIds);
                opts.add("demo");
                opts.add("stop");
                return filter(opts, cur);
            }
            case "set", "add":
                if (a.length == 2) {
                    return filter(plugin.boards().values().stream()
                            .filter(b -> b.type == Board.Type.CUSTOM).map(b -> b.id).collect(Collectors.toList()), cur);
                }
                if (a.length == 3) {
                    return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()), cur);
                }
                return List.of();
            default:
                return List.of();
        }
    }

    private static List<String> withAll(List<String> ids) {
        List<String> l = new ArrayList<>(ids);
        l.add("all");
        return l;
    }

    private static List<String> filter(List<String> src, String cur) {
        return src.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(cur)).collect(Collectors.toList());
    }
}
