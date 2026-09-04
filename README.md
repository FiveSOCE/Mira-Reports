# MiraReports

MiraReports is the player-reporting and staff triage-queue system for the Mira Paper server suite. It gives players a simple report flow while preserving report IDs, assignment, staff resolution details and historical records.

## Download

[**Download MiraReports v0.1.1**](https://github.com/FiveSOCE/Mira-Reports/releases/download/v0.1.1/MiraReports-0.1.1.jar)

[View All Releases](https://github.com/FiveSOCE/Mira-Reports/releases)

## Requirements / Dependencies

- Paper 1.21.11
- Java 21
- MiraCore 0.2.0 or newer
- MiraStaff optional integration
- MiraPunishments optional enforcement integration

## How MiraReports Works

A player report creates a persistent report record with a unique ID and `OPEN` state. Staff are notified immediately and can browse the unresolved queue. A staff member can claim a report, moving it to `CLAIMED` while retaining it in the unresolved queue, then close it with a resolution when handling is finished. Closed reports remain persistent rather than disappearing.

v0.1.1 adds configurable report cooldowns and duplicate-open-report prevention so one reporter cannot spam the same target while an unresolved case already exists. Report create, claim and close actions are recorded through MiraCore audit history and typed `ReportCreatedEvent` / `ReportStatusChangeEvent` lifecycle events are emitted for MiraStaff, MiraPunishments or future automation.

MiraReports does not issue punishments itself. MiraPunishments remains the enforcement authority.

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/report <player> <reason>` | `mirareports.report` | Creates a report against the selected player. |
| `/reports [page]` | `mirareports.staff` | Shows the unresolved report queue. |
| `/reports info <id>` | `mirareports.staff` | Shows complete details for one report ID. |
| `/reports mine [page]` | `mirareports.staff` | Shows reports currently assigned to the requesting staff member. |
| `/reportclaim <id>` | `mirareports.staff` | Claims an unresolved report for handling. |
| `/reportclose <id> [resolution]` | `mirareports.staff` | Closes a report and records the final resolution. |

## Permissions

| Permission | Default | What it does |
| --- | --- | --- |
| `mirareports.report` | Everyone | Allows players to submit reports. |
| `mirareports.staff` | OP | Allows queue access, assignment, detail viewing and closure. |

## Configuration

Important settings:

- `report-cooldown-seconds` - minimum delay between player report submissions.
- `block-duplicate-open-report` - prevents one reporter from opening multiple unresolved reports against the same target.
- `page-size` - report rows per queue page.

## API / Integration

`ReportsApi` is registered through Bukkit ServicesManager and MiraCore. It exposes unresolved reports, target history, staff assignments, ID lookup, duplicate checks, claiming and closure.

Typed lifecycle events:

- `ReportCreatedEvent`
- `ReportStatusChangeEvent`

## Persistence

Reports are stored in `plugins/MiraReports/reports.yml`. Assignment, closure and timestamps survive restart.

## Building

```bash
gradle clean build
```

The output JAR is created in `build/libs/`.
