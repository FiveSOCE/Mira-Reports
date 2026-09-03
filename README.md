# MiraReports

MiraReports is the player-reporting and staff report-queue system for the Mira Paper server suite. It gives players a simple way to report others while preserving report IDs, status, staff resolution details and historical records.

## Download

[**Download MiraReports v0.1.0**](https://github.com/FiveSOCE/Mira-Reports/releases/download/v0.1.0/MiraReports-0.1.0.jar)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21

## How MiraReports Works

A player report creates a persistent report record with a unique ID and `OPEN` state. Staff with report access are notified and can browse the open queue by page. When a report is handled, staff close it with an optional resolution note. The report remains in history with its closer, close timestamp and final resolution instead of being deleted.

MiraReports also exposes a public Bukkit ServicesManager API so other Mira moderation tools can query report state.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/report <player> <reason>` | `mirareports.report` | Creates a report against the selected player. |
| `/reports [page]` | `mirareports.staff` | Shows the paginated staff report queue. |
| `/reportclose <id> [resolution]` | `mirareports.staff` | Closes a report and optionally records the staff resolution. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `mirareports.report` | Everyone | Allows players to submit reports. |
| `mirareports.staff` | OP | Allows viewing and closing reports and receiving staff-facing report access. |
