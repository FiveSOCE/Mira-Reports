# MiraReports

Player reporting and staff report queue for Paper 1.21.11 / Java 21.

## Current release

**v0.1.0**

Direct download:
https://github.com/FiveSOCE/Mira-Reports/releases/download/v0.1.0/MiraReports-0.1.0.jar

All releases:
https://github.com/FiveSOCE/Mira-Reports/releases

## Features

- Player-to-player reporting
- Persistent report IDs and history
- Staff notifications for new reports
- Paginated open report queue
- Close reports with staff resolution notes
- OPEN/CLOSED lifecycle with closer and timestamp
- Public Bukkit ServicesManager API

## Commands

- `/report <player> <reason>`
- `/reports [page]`
- `/reportclose <id> [resolution]`

## Build

`./gradlew build`

Output: `build/libs/MiraReports-0.1.0.jar`
