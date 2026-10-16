# Daybook

## Mission

**Get up at the same time every morning, get outside, and keep an honest record of each day.**

Daybook is a personal Android app for one person. It treats a *day* as the basic unit: each day has a morning, the food eaten, and maybe a workout. The alarm stops only when you scan a QR code on the porch, so being up and being outside become one action that can't be faked from bed. Everything else is a fast log that builds into a history worth looking back on.

### Principles

- **The day is the record.** Days roll over at midnight. The weekly routine is a template laid over each day; once you start logging a day, it owns its data, and editing the routine never rewrites the past.
- **Hard to cheat, easy to plan.** You can change or turn off an alarm until 10pm the night before; after that it's locked. There's no snooze and no emergency exit. Turning the alarm off ahead of time is fine, and those days don't count against you.
- **Honest data.** Manual mornings are marked as manual. Backfilled food is tagged. A day with the alarm off is excluded from scoring instead of counting as missed.
- **Low-friction logging.** Food is free text. Sets are pre-filled from last time. One tap repeats a whole exercise.
- **Local and private.** No account and no server. Everything lives on the phone.

## How it works

| Area | Where |
|---|---|
| Alarm rules (lock time, which alarm applies, next alarm, scoring) | `domain/AlarmRules.kt`, plain Kotlin with unit tests |
| Storage (Room/SQLite; dates `yyyy-MM-dd`, times `HH:mm`, both local) | `data/Entities.kt`, `data/Daos.kt`, `data/*Repository.kt` |
| Settings (porch code secret, lock-screen toggle; DataStore) | `data/SettingsStore.kt` |
| Alarm: one exact `setAlarmClock` alarm, a foreground service that rings, a full-screen scan screen | `alarm/` |
| Porch QR: generate (ZXing) and scan (CameraX + ML Kit, offline) | `scan/Qr.kt` |
| Lock-screen summary: drawn as a lock-only wallpaper, redrawn on data changes, at 10pm and at midnight | `wallpaper/` |
| Targets: Mifflin–St Jeor × activity + goal, macros from body weight and fat % | `domain/Targets.kt` |
| Food estimates: one Claude API call per entry (`claude-haiku-5-5`, structured JSON) when logged or its text changes; retried in the background by WorkManager, capped at 8 tries; every API error mapped to a plain message (`ai/EstimateErrors.kt`); API key encrypted with the Android Keystore | `ai/` |
| Steps: daily total read from Health Connect (the phone counts steps itself), shown against a goal and frozen per day | `steps/`, `data/StepSource.kt` |
| Library: saved meals (exact numbers, tap to log, servings) and the exercise catalog (rename, hide, delete if unused), under Setup | `data/LibraryRepository.kt`, `ui/LibraryScreens.kt` |
| Finalizing days: once a day ends, its alarm, planned workout and targets are frozen so later routine or profile edits don't rewrite history | `data/DayFinalizer.kt` |
| Shared one-line summaries for the Day screen and lock screen | `domain/DayText.kt` |
| UI: Compose, four tabs (Day, Week, Routine, Setup), one ViewModel per screen | `ui/` |

Key data rules:
- A day's alarm is its override if set (a time or `off`), otherwise the routine. Once the day locks, the time that was armed (`day.scheduled_alarm`) is frozen.
- Workouts copy the template's exercise names onto the day. Each exercise has `sets[]` of weight and reps; the set count is just the number of rows.
- Exercise names are one shared catalog, matched without regard to case.
- Food text is the source of truth. Estimates (calorie range, macros, assumptions, model) are derived from it; editing the text re-estimates.
- A saved meal's numbers are copied onto the entry when it's logged (× servings), so editing or deleting the meal never changes past days. Changing a meal entry's text turns it back into an AI entry.
- Every schema change ships with a migration test (`MigrationTest`); never a destructive fallback.

## Build and run

Requires JDK 17 and the Android SDK at `~/Android/Sdk` (platform 36). Day-to-day work goes through `scripts/`. Anything that touches a device picks the plugged-in phone first, then a running emulator; set `ANDROID_SERIAL` to choose one yourself.

| Command | What it does |
|---|---|
| `scripts/install` | Build, install and open Daybook (`--no-launch` to skip opening) |
| `scripts/test` | Unit tests, plus device tests if an emulator is running (`unit` or `device` for just one) |
| `scripts/emulator` | Start the emulator and wait until it's ready (`--headless` for no window, `--stop` to shut it down) |
| `scripts/logs` | Follow the app's log live (`--crash` for recent crashes) |
| `scripts/screenshot` | Capture the device screen to `~/Downloads` (`--last` copies the newest screenshot you took on the phone) |
| `scripts/backup-db` | Copy the database off the device into `backups/<timestamp>/`, which git ignores |
| `scripts/alarm-check` | Show the armed alarm and permissions (`--doze` or `--reboot` to stress-test it) |
| `LIVE_API=1 ./gradlew testDebugUnitTest --tests '*LiveApiErrorTest*'` | Check a real API error end to end, using a deliberately invalid key (free) |

**Wireless:** the phone is paired for wireless debugging, so the scripts work over home Wi-Fi without a cable. Turn on Developer options → Wireless debugging (or its quick settings tile) and `adb` reconnects on its own; if it doesn't, run `adb connect <IP:port>` with the address shown on that screen. Keep it allowed on the home network only, and off when not in use. Revoking "USB debugging authorizations" in Developer options un-pairs every computer.

**Device tests never run on the phone.** Gradle uninstalls the app after them, which deletes all of Daybook's data, so `scripts/test` refuses anything but the emulator. Run `./gradlew connectedDebugAndroidTest` by hand only if you know which device it will hit.

First run on a phone: open **Setup**, clear the permission checklist, print the porch code and scan it once to arm alarms, and try **Test alarm in 1 minute**.

## Backlog

Bugs, features and tech debt are tracked in [BACKLOG.md](BACKLOG.md).
