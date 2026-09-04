package gg.mira.reports.api.event;

import gg.mira.reports.MiraReportsPlugin.Report;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public final class ReportStatusChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Report report;

    public ReportStatusChangeEvent(Report report) { this.report = report; }
    public Report report() { return report; }

    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static @NotNull HandlerList getHandlerList() { return HANDLERS; }
}
