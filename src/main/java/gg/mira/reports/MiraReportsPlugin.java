package gg.mira.reports;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class MiraReportsPlugin extends JavaPlugin {
    private static final String PREFIX = "&5&lMira &8>> &r";
    private ReportService service;

    @Override public void onEnable() {
        service = new ReportService(this);
        getServer().getServicesManager().register(ReportsApi.class, service, this, ServicePriority.Normal);
    }

    @Override public void onDisable() {
        service.save();
        getServer().getServicesManager().unregisterAll(this);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "report" -> report(sender, args);
            case "reports" -> reports(sender, args);
            case "reportclose" -> close(sender, args);
            default -> false;
        };
    }

    private boolean report(CommandSender sender, String[] args) {
        if (!(sender instanceof Player reporter)) { msg(sender, "&cPlayers only."); return true; }
        if (args.length < 2) { msg(sender, "&cUsage: /report <player> <reason>"); return true; }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (target.getUniqueId().equals(reporter.getUniqueId())) { msg(sender, "&cYou cannot report yourself."); return true; }
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Report entry = service.create(reporter.getUniqueId(), reporter.getName(), target.getUniqueId(), target.getName(), reason);
        msg(reporter, "&aReport submitted. &7ID: &f" + entry.id());
        for (Player online : Bukkit.getOnlinePlayers()) if (online.hasPermission("mirareports.staff")) msg(online, "&8[&cReport&8] &f" + reporter.getName() + " &7reported &f" + safeName(target) + " &7for &f" + reason + " &8[" + entry.id() + "]");
        return true;
    }

    private boolean reports(CommandSender sender, String[] args) {
        int page = args.length >= 1 ? parseInt(args[0], 1) : 1;
        List<Report> open = service.openReports();
        int pages = Math.max(1, (open.size() + 7) / 8);
        page = Math.max(1, Math.min(page, pages));
        msg(sender, "&6Open Reports &8(" + page + "/" + pages + ")");
        int from = (page - 1) * 8;
        for (int i = from; i < Math.min(open.size(), from + 8); i++) {
            Report r = open.get(i);
            msg(sender, "&e" + r.id() + " &f" + r.targetName() + " &7by &f" + r.reporterName() + " &8- &7" + r.reason());
        }
        if (open.isEmpty()) msg(sender, "&7No open reports.");
        return true;
    }

    private boolean close(CommandSender sender, String[] args) {
        if (args.length < 1) { msg(sender, "&cUsage: /reportclose <id> [resolution]"); return true; }
        String resolution = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Closed by staff";
        if (!service.close(args[0], sender.getName(), resolution)) msg(sender, "&cOpen report not found.");
        else msg(sender, "&aReport closed.");
        return true;
    }

    private void msg(CommandSender sender, String raw) { sender.sendMessage(ChatColor.translateAlternateColorCodes('&', PREFIX + raw)); }
    private static String safeName(OfflinePlayer player) { return player.getName() == null ? player.getUniqueId().toString() : player.getName(); }
    private static int parseInt(String s, int fallback) { try { return Integer.parseInt(s); } catch (NumberFormatException ex) { return fallback; } }

    public interface ReportsApi {
        List<Report> openReports();
        List<Report> reportsFor(UUID target);
        Optional<Report> byId(String id);
        boolean close(String id, String staff, String resolution);
    }

    public enum Status { OPEN, CLOSED }
    public record Report(String id, UUID reporter, String reporterName, UUID target, String targetName, String reason,
                         long createdAt, Status status, String closedBy, long closedAt, String resolution) {}

    public static final class ReportService implements ReportsApi {
        private final MiraReportsPlugin plugin;
        private final File file;
        private final Map<String, Report> reports = new LinkedHashMap<>();

        ReportService(MiraReportsPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "reports.yml");
            load();
        }

        Report create(UUID reporter, String reporterName, UUID target, String targetName, String reason) {
            String id = Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
            Report report = new Report(id, reporter, reporterName, target, targetName == null ? target.toString() : targetName,
                    reason, System.currentTimeMillis(), Status.OPEN, "", 0L, "");
            reports.put(id, report);
            save();
            return report;
        }

        @Override public List<Report> openReports() {
            return reports.values().stream().filter(r -> r.status() == Status.OPEN).sorted(Comparator.comparingLong(Report::createdAt)).toList();
        }

        @Override public List<Report> reportsFor(UUID target) {
            return reports.values().stream().filter(r -> r.target().equals(target)).sorted(Comparator.comparingLong(Report::createdAt).reversed()).toList();
        }

        @Override public Optional<Report> byId(String id) { return Optional.ofNullable(reports.get(id.toUpperCase(Locale.ROOT))); }

        @Override public boolean close(String id, String staff, String resolution) {
            String key = id.toUpperCase(Locale.ROOT);
            Report old = reports.get(key);
            if (old == null || old.status() != Status.OPEN) return false;
            reports.put(key, new Report(old.id(), old.reporter(), old.reporterName(), old.target(), old.targetName(), old.reason(),
                    old.createdAt(), Status.CLOSED, staff, System.currentTimeMillis(), resolution));
            save();
            return true;
        }

        void load() {
            plugin.getDataFolder().mkdirs();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection root = yaml.getConfigurationSection("reports");
            if (root == null) return;
            for (String id : root.getKeys(false)) {
                String b = id + ".";
                try {
                    reports.put(id, new Report(id,
                            UUID.fromString(root.getString(b + "reporter")), root.getString(b + "reporter-name", "unknown"),
                            UUID.fromString(root.getString(b + "target")), root.getString(b + "target-name", "unknown"), root.getString(b + "reason", "No reason"),
                            root.getLong(b + "created-at"), Status.valueOf(root.getString(b + "status", "OPEN")), root.getString(b + "closed-by", ""),
                            root.getLong(b + "closed-at"), root.getString(b + "resolution", "")));
                } catch (Exception ignored) {}
            }
        }

        synchronized void save() {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Report r : reports.values()) {
                String b = "reports." + r.id() + ".";
                yaml.set(b + "reporter", r.reporter().toString()); yaml.set(b + "reporter-name", r.reporterName());
                yaml.set(b + "target", r.target().toString()); yaml.set(b + "target-name", r.targetName()); yaml.set(b + "reason", r.reason());
                yaml.set(b + "created-at", r.createdAt()); yaml.set(b + "status", r.status().name()); yaml.set(b + "closed-by", r.closedBy());
                yaml.set(b + "closed-at", r.closedAt()); yaml.set(b + "resolution", r.resolution());
            }
            try { yaml.save(file); } catch (IOException ex) { plugin.getLogger().severe("Could not save reports.yml: " + ex.getMessage()); }
        }
    }
}
