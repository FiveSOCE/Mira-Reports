package gg.mira.reports;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import com.mira.core.api.PaginationService;
import gg.mira.reports.api.event.ReportCreatedEvent;
import gg.mira.reports.api.event.ReportStatusChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

public final class MiraReportsPlugin extends JavaPlugin implements TabExecutor {
    private MiraCore core;
    private ReportService service;
    private final Map<UUID, Long> lastReportAt = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        core = MiraCoreProvider.require();
        service = new ReportService(this);

        getServer().getServicesManager().register(ReportsApi.class, service, this, ServicePriority.Normal);
        core.services().register(ReportsApi.class, service);
        core.modules().register(this, "MiraReports");
        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Persistent report queue, staff assignment and audit integration ready");

        for (String commandName : List.of("report", "reports", "reportclaim", "reportclose")) {
            var pluginCommand = getCommand(commandName);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(this);
                pluginCommand.setTabCompleter(this);
            }
        }

        getLogger().info("MiraReports v" + getPluginMeta().getVersion() + " enabled with "
                + service.openReports().size() + " unresolved report(s).");
    }

    @Override
    public void onDisable() {
        if (service != null) service.save();
        getServer().getServicesManager().unregisterAll(this);
        if (core != null) {
            if (service != null) core.services().unregister(ReportsApi.class, service);
            core.modules().unregister(this);
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "report" -> report(sender, args);
            case "reports" -> reports(sender, args);
            case "reportclaim" -> claim(sender, args);
            case "reportclose" -> close(sender, args);
            default -> false;
        };
    }

    private boolean report(CommandSender sender, String[] args) {
        if (!(sender instanceof Player reporter)) {
            msg(sender, "&cPlayers only.");
            return true;
        }
        if (args.length < 2) {
            msg(sender, "&eUsage: /report <player> <reason>");
            return true;
        }

        OfflinePlayer target = resolve(args[0]);
        if (target == null) {
            msg(sender, "&cPlayer not found.");
            return true;
        }
        if (target.getUniqueId().equals(reporter.getUniqueId())) {
            msg(sender, "&cYou cannot report yourself.");
            return true;
        }

        long cooldown = Math.max(0L, getConfig().getLong("report-cooldown-seconds", 60L)) * 1000L;
        long previous = lastReportAt.getOrDefault(reporter.getUniqueId(), 0L);
        long remaining = previous + cooldown - System.currentTimeMillis();
        if (remaining > 0) {
            msg(sender, "&eYou can submit another report in &f" + ((remaining + 999L) / 1000L) + "s&e.");
            return true;
        }

        if (getConfig().getBoolean("block-duplicate-open-report", true)
                && service.hasOpenReport(reporter.getUniqueId(), target.getUniqueId())) {
            msg(sender, "&eYou already have an unresolved report open against that player.");
            return true;
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (reason.isBlank()) {
            msg(sender, "&cA report reason is required.");
            return true;
        }

        Report entry = service.create(reporter.getUniqueId(), reporter.getName(),
                target.getUniqueId(), displayName(target), reason);
        lastReportAt.put(reporter.getUniqueId(), System.currentTimeMillis());
        Bukkit.getPluginManager().callEvent(new ReportCreatedEvent(entry));
        core.audit().record("MiraReports", "REPORT_CREATED", reporter.getUniqueId(), reporter.getName(),
                entry.id(), "Player report submitted",
                Map.of("target", target.getUniqueId().toString(), "targetName", displayName(target), "reason", reason));

        msg(reporter, "&aReport submitted. &7ID: &f" + entry.id());
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("mirareports.staff")) {
                msg(online, "&8[&cReport&8] &f" + reporter.getName() + " &7reported &f"
                        + displayName(target) + " &7for &f" + reason + " &8[" + entry.id() + "]");
            }
        }
        return true;
    }

    private boolean reports(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("info")) {
            if (args.length < 2) {
                msg(sender, "&eUsage: /reports info <id>");
                return true;
            }
            Report report = service.byId(args[1]).orElse(null);
            if (report == null) {
                msg(sender, "&cReport not found.");
                return true;
            }
            showInfo(sender, report);
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("mine")) {
            List<Report> mine = service.assignedTo(sender.getName());
            showPage(sender, mine, args.length >= 2 ? parseInt(args[1], 1) : 1, "My Reports");
            return true;
        }

        int page = args.length >= 1 ? parseInt(args[0], 1) : 1;
        showPage(sender, service.openReports(), page, "Unresolved Reports");
        return true;
    }

    private void showPage(CommandSender sender, List<Report> reports, int requestedPage, String title) {
        int pageSize = Math.max(1, Math.min(20, getConfig().getInt("page-size", 8)));
        PaginationService.Page<Report> page = core.pagination().page(reports, requestedPage, pageSize);
        msg(sender, "&6" + title + " &8(" + page.page() + "/" + page.pages() + ")");
        for (Report report : page.values()) {
            String assignment = report.status() == Status.CLAIMED ? " &b[" + report.assignedTo() + "]" : "";
            msg(sender, "&e" + report.id() + " &f" + report.targetName() + " &7by &f"
                    + report.reporterName() + " &8- &7" + report.reason() + assignment);
        }
        if (reports.isEmpty()) msg(sender, "&7No matching reports.");
    }

    private void showInfo(CommandSender sender, Report report) {
        msg(sender, "&6Report &f" + report.id());
        msg(sender, "&7Status: &f" + report.status() + " &7Created: &f" + Instant.ofEpochMilli(report.createdAt()));
        msg(sender, "&7Reporter: &f" + report.reporterName() + " &8(" + report.reporter() + ")");
        msg(sender, "&7Target: &f" + report.targetName() + " &8(" + report.target() + ")");
        msg(sender, "&7Reason: &f" + report.reason());
        if (!report.assignedTo().isBlank()) msg(sender, "&7Assigned: &f" + report.assignedTo());
        if (report.status() == Status.CLOSED) {
            msg(sender, "&7Closed by: &f" + report.closedBy() + " &7at &f" + Instant.ofEpochMilli(report.closedAt()));
            msg(sender, "&7Resolution: &f" + report.resolution());
        }
    }

    private boolean claim(CommandSender sender, String[] args) {
        if (args.length < 1) {
            msg(sender, "&eUsage: /reportclaim <id>");
            return true;
        }
        Report updated = service.claim(args[0], sender.getName()).orElse(null);
        if (updated == null) {
            msg(sender, "&cThat report is missing, closed, or already claimed by another staff member.");
            return true;
        }
        Bukkit.getPluginManager().callEvent(new ReportStatusChangeEvent(updated));
        audit(sender, "REPORT_CLAIMED", updated.id(), Map.of("target", updated.target().toString()));
        msg(sender, "&aClaimed report &f" + updated.id() + "&a.");
        return true;
    }

    private boolean close(CommandSender sender, String[] args) {
        if (args.length < 1) {
            msg(sender, "&eUsage: /reportclose <id> [resolution]");
            return true;
        }
        String resolution = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim()
                : "Closed by staff";
        Report closed = service.close(args[0], sender.getName(), resolution).orElse(null);
        if (closed == null) {
            msg(sender, "&cOpen report not found.");
            return true;
        }
        Bukkit.getPluginManager().callEvent(new ReportStatusChangeEvent(closed));
        audit(sender, "REPORT_CLOSED", closed.id(),
                Map.of("target", closed.target().toString(), "resolution", closed.resolution()));
        msg(sender, "&aReport &f" + closed.id() + " &aclosed.");
        return true;
    }

    private void audit(CommandSender sender, String action, String target, Map<String, String> metadata) {
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        core.audit().record("MiraReports", action, actor, sender.getName(), target, action, metadata);
    }

    private OfflinePlayer resolve(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return Bukkit.getOfflinePlayer(UUID.fromString(raw)); }
        catch (IllegalArgumentException ignored) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(raw);
            return player.getName() != null || player.hasPlayedBefore() || player.isOnline() ? player : null;
        }
    }

    private String displayName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private void msg(CommandSender sender, String raw) { core.messages().send(sender, raw); }
    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { return fallback; }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("report") && args.length == 1) {
            return complete(args[0], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (name.equals("reports")) {
            if (args.length == 1) return complete(args[0], List.of("info", "mine"));
            if (args.length == 2 && args[0].equalsIgnoreCase("info")) return complete(args[1], service.openReports().stream().map(Report::id).toList());
        }
        if ((name.equals("reportclaim") || name.equals("reportclose")) && args.length == 1) {
            return complete(args[0], service.openReports().stream().map(Report::id).toList());
        }
        return List.of();
    }

    private static List<String> complete(String prefix, Collection<String> values) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().sorted().toList();
    }

    public interface ReportsApi {
        List<Report> openReports();
        List<Report> reportsFor(UUID target);
        List<Report> assignedTo(String staff);
        Optional<Report> byId(String id);
        boolean hasOpenReport(UUID reporter, UUID target);
        Optional<Report> claim(String id, String staff);
        Optional<Report> close(String id, String staff, String resolution);
    }

    public enum Status { OPEN, CLAIMED, CLOSED }

    public record Report(String id, UUID reporter, String reporterName, UUID target, String targetName, String reason,
                         long createdAt, Status status, String assignedTo, long assignedAt,
                         String closedBy, long closedAt, String resolution) { }

    public static final class ReportService implements ReportsApi {
        private final MiraReportsPlugin plugin;
        private final File file;
        private final Map<String, Report> reports = new LinkedHashMap<>();

        ReportService(MiraReportsPlugin plugin) {
            this.plugin = plugin;
            this.file = new File(plugin.getDataFolder(), "reports.yml");
            load();
        }

        synchronized Report create(UUID reporter, String reporterName, UUID target, String targetName, String reason) {
            String id;
            do {
                id = Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT)
                        + Integer.toString(new Random().nextInt(36 * 36), 36).toUpperCase(Locale.ROOT);
            } while (reports.containsKey(id));
            Report report = new Report(id, reporter, reporterName, target,
                    targetName == null ? target.toString() : targetName, reason,
                    System.currentTimeMillis(), Status.OPEN, "", 0L, "", 0L, "");
            reports.put(id, report);
            save();
            return report;
        }

        @Override
        public synchronized List<Report> openReports() {
            return reports.values().stream().filter(report -> report.status() != Status.CLOSED)
                    .sorted(Comparator.comparingLong(Report::createdAt)).toList();
        }

        @Override
        public synchronized List<Report> reportsFor(UUID target) {
            return reports.values().stream().filter(report -> report.target().equals(target))
                    .sorted(Comparator.comparingLong(Report::createdAt).reversed()).toList();
        }

        @Override
        public synchronized List<Report> assignedTo(String staff) {
            if (staff == null) return List.of();
            return reports.values().stream()
                    .filter(report -> report.status() == Status.CLAIMED && report.assignedTo().equalsIgnoreCase(staff))
                    .sorted(Comparator.comparingLong(Report::createdAt)).toList();
        }

        @Override
        public synchronized Optional<Report> byId(String id) {
            if (id == null) return Optional.empty();
            return Optional.ofNullable(reports.get(id.toUpperCase(Locale.ROOT)));
        }

        @Override
        public synchronized boolean hasOpenReport(UUID reporter, UUID target) {
            return reports.values().stream().anyMatch(report -> report.status() != Status.CLOSED
                    && report.reporter().equals(reporter) && report.target().equals(target));
        }

        @Override
        public synchronized Optional<Report> claim(String id, String staff) {
            Report old = byId(id).orElse(null);
            if (old == null || old.status() == Status.CLOSED) return Optional.empty();
            if (old.status() == Status.CLAIMED && !old.assignedTo().equalsIgnoreCase(staff)) return Optional.empty();
            Report updated = new Report(old.id(), old.reporter(), old.reporterName(), old.target(), old.targetName(),
                    old.reason(), old.createdAt(), Status.CLAIMED, staff, System.currentTimeMillis(),
                    old.closedBy(), old.closedAt(), old.resolution());
            reports.put(old.id(), updated);
            save();
            return Optional.of(updated);
        }

        @Override
        public synchronized Optional<Report> close(String id, String staff, String resolution) {
            Report old = byId(id).orElse(null);
            if (old == null || old.status() == Status.CLOSED) return Optional.empty();
            Report updated = new Report(old.id(), old.reporter(), old.reporterName(), old.target(), old.targetName(),
                    old.reason(), old.createdAt(), Status.CLOSED, old.assignedTo(), old.assignedAt(),
                    staff, System.currentTimeMillis(), resolution == null ? "Closed by staff" : resolution);
            reports.put(old.id(), updated);
            save();
            return Optional.of(updated);
        }

        synchronized void load() {
            plugin.getDataFolder().mkdirs();
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            reports.clear();
            ConfigurationSection root = yaml.getConfigurationSection("reports");
            if (root == null) return;
            for (String id : root.getKeys(false)) {
                String base = id + ".";
                try {
                    String statusRaw = root.getString(base + "status", "OPEN");
                    reports.put(id.toUpperCase(Locale.ROOT), new Report(id.toUpperCase(Locale.ROOT),
                            UUID.fromString(root.getString(base + "reporter")),
                            root.getString(base + "reporter-name", "unknown"),
                            UUID.fromString(root.getString(base + "target")),
                            root.getString(base + "target-name", "unknown"),
                            root.getString(base + "reason", "No reason"),
                            root.getLong(base + "created-at"), Status.valueOf(statusRaw),
                            root.getString(base + "assigned-to", ""), root.getLong(base + "assigned-at"),
                            root.getString(base + "closed-by", ""), root.getLong(base + "closed-at"),
                            root.getString(base + "resolution", "")));
                } catch (Exception ignored) { }
            }
        }

        synchronized void save() {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Report report : reports.values()) {
                String base = "reports." + report.id() + ".";
                yaml.set(base + "reporter", report.reporter().toString());
                yaml.set(base + "reporter-name", report.reporterName());
                yaml.set(base + "target", report.target().toString());
                yaml.set(base + "target-name", report.targetName());
                yaml.set(base + "reason", report.reason());
                yaml.set(base + "created-at", report.createdAt());
                yaml.set(base + "status", report.status().name());
                yaml.set(base + "assigned-to", report.assignedTo());
                yaml.set(base + "assigned-at", report.assignedAt());
                yaml.set(base + "closed-by", report.closedBy());
                yaml.set(base + "closed-at", report.closedAt());
                yaml.set(base + "resolution", report.resolution());
            }
            try { yaml.save(file); }
            catch (IOException ex) { plugin.getLogger().severe("Could not save reports.yml: " + ex.getMessage()); }
        }
    }
}
