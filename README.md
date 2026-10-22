# Daybook

> A personal Android app, built for one person and one phone, and shared as-is. There's no support and no roadmap beyond [BACKLOG.md](BACKLOG.md). You're welcome to read it, fork it or borrow from it ([MIT](LICENSE)).

## Mission

**Get up at the same time every morning, get outside, and keep an honest record of each day.**

Daybook treats a *day* as the basic unit: each day has a morning, the food eaten, maybe a workout, a step count and an evening check-in. The alarm stops only when you scan a QR code on the porch, so being up and being outside become one action that can't be faked from bed. Everything else is a fast log that builds into a history worth looking back on.

### Principles

- **The day is the record.** Days roll over at midnight. The weekly routine is a template laid over each day; once you start logging a day, it owns its data, and editing the routine never rewrites the past.
- **Hard to cheat, easy to plan.** You can change or turn off an alarm until 10pm the night before; after that it's locked. There's no snooze and no emergency exit. Turning the alarm off ahead of time is fine, and those days don't count against you.
- **Honest data.** Manual mornings are marked as manual. Backfilled food is tagged. A day with the alarm off is excluded from scoring instead of counting as missed.
- **Low-friction logging.** Food is free text, a saved meal or a barcode scan. Sets are pre-filled from last time, and the keyboard's Next key walks through them.
- **Local and private.** No account and no server of its own; everything lives on the phone. It makes exactly two kinds of network call: food text you log goes to the Claude API with *your own* API key, and barcode numbers you scan go to [Open Food Facts](https://world.openfoodfacts.org).

## How it works

| Area | Where |
|---|---|
| Alarm rules (lock time, which alarm applies, next alarm, scoring) | `domain/AlarmRules.kt`, plain Kotlin with unit tests |
| Storage (Room/SQLite; dates `yyyy-MM-dd`, times `HH:mm`, both local) | `data/Entities.kt`, `data/Daos.kt`, `data/*Repository.kt` |
| Settings (porch code secret, profile, step goal, lock-screen toggle; DataStore) | `data/SettingsStore.kt` |
| Alarm: one exact `setAlarmClock` alarm, a foreground service that rings the phone's default alarm sound, a full-screen scan screen | `alarm/` |
| Porch QR: generate (ZXing) and scan (CameraX + ML Kit, offline) | `scan/Qr.kt` |
| Lock-screen summary: drawn as a lock-only wallpaper, redrawn when its content changes, at 10pm and at midnight | `wallpaper/` |
| Targets: Mifflin–St Jeor × activity + goal, macros from body weight and fat % | `domain/Targets.kt` |
| Food estimates: one Claude API call per entry (`claude-haiku-5-5`, structured JSON) when logged or its text changes; retried in the background by WorkManager, capped at 8 tries; every API error mapped to a plain message (`ai/EstimateErrors.kt`); API key encrypted with the Android Keystore | `ai/` |
| Barcode scanning: EAN/UPC codes looked up on Open Food Facts and checked in the meal form before saving; rescans come from the library and work offline. Answers are treated as untrusted: HTTPS only, no redirects, size and time caps, sanity limits on numbers, plain short text | `data/BarcodeLookup.kt`, `ui/BarcodeScanDialog.kt` |
| Library: saved meals (exact numbers, tap to log, servings, optional barcode) and the exercise catalog (seeded with common lifts and a typical Planet Fitness lineup; rename, hide, delete if unused), under Setup | `data/LibraryRepository.kt`, `data/ExerciseSeed.kt`, `ui/LibraryScreens.kt` |
| Workout logging: Enter walks weight → reps → next set; an empty field's grey hint (the set above, else last time) is accepted with Enter; exercises can be moved or removed | `ui/DayScreen.kt`, `domain/SetHints.kt` |
| Steps: counted from the phone's hardware step sensor (running total since boot), checked every 15 minutes, at 23:59 and at boot; the rules for reboots and midnight are in `domain/StepMath.kt`; shown against a goal and frozen per day | `steps/`, `data/StepCounter.kt` |
| Evening check-in: mood and energy (1–5) per day, with a 9pm reminder that skips rated days | `checkin/`, `domain/Ratings.kt` |
| Week view: each day's morning, workout, steps and calories | `ui/WeekScreen.kt` |
| Finalizing days: once a day ends, its alarm, planned workout, targets and steps are frozen so later routine or profile edits don't rewrite history | `data/DayFinalizer.kt` |
| Self-backups: a consistent copy of the database and settings daily (keeps 14) in the app's shared-storage folder, readable over `adb` | `data/Backups.kt` |
| Shared one-line summaries for the Day screen and lock screen | `domain/DayText.kt` |
| UI: Compose, four tabs (Day, Week, Routine, Setup), one ViewModel per screen | `ui/` |

Key data rules:
- A day's alarm is its override if set (a time or `off`), otherwise the routine. Once the day locks, the time that was armed (`day.scheduled_alarm`) is frozen.
- Workouts copy the template's exercises onto the day. Each exercise has `sets[]` of weight and reps; the set count is just the number of rows.
- Exercise names are one shared catalog, matched without regard to case.
- Food text is the source of truth. Estimates (calorie range, macros, assumptions, model) are derived from it; editing the text re-estimates.
- A saved meal's numbers are copied onto the entry when it's logged (× servings), so editing or deleting the meal never changes past days. Changing a meal entry's text turns it back into an AI entry.
- Every schema change ships with a migration test (`MigrationTest`); never a destructive fallback.

## Building it yourself

Requires JDK 17 and the Android SDK at `~/Android/Sdk` (platform 36), or `sdk.dir` in `local.properties`.

- **Debug builds need no setup:** `./gradlew assembleDebug`, or `scripts/install` with a device connected.
- **Release builds need your own signing key.** Create a keystore in `signing/` (git-ignored) and a `signing/keystore.properties` next to it:
  ```properties
  storeFile=daybook.keystore   # relative to signing/
  storePassword=…
  keyAlias=…
  keyPassword=…
  ```
  Back the keystore up somewhere safe: Android only installs updates signed with the same key, so losing it means uninstalling (and losing the app's data) to install a new build.
- **In the app:** open **Setup**, clear the permission checklist, add a Claude API key for food estimates (a separate workspace with a spend limit is a good idea), print the porch code and scan it once to arm alarms, then try **Test alarm in 1 minute**.

## Scripts

Day-to-day work goes through `scripts/`. Anything that touches a device picks the plugged-in phone first, then a running emulator; set `ANDROID_SERIAL` to choose one yourself.

| Command | What it does |
|---|---|
| `scripts/release` | **Ship to the phone:** tests, release build, backup, install as an update, check no data was lost, check the alarm, tag `release-N`. Needs a clean working tree. |
| `scripts/install` | Build, install and open a **debug** build (`--no-launch` to skip opening) |
| `scripts/test` | Unit tests, plus device tests if an emulator is running (`unit` or `device` for just one) |
| `scripts/emulator` | Start the emulator and wait until it's ready (`--headless` for no window, `--stop` to shut it down) |
| `scripts/logs` | Follow the app's log live (`--crash` for recent crashes) |
| `scripts/screenshot` | Capture the device screen to `~/Downloads` (`--last` copies the newest screenshot taken on the phone) |
| `scripts/backup-db` | Copy the database off the device into `backups/<timestamp>/`, which git ignores |
| `scripts/alarm-check` | Show the armed alarm and permissions (`--doze` or `--reboot` to stress-test it) |
| `LIVE_API=1 ./gradlew testDebugUnitTest --tests '*LiveApiErrorTest*'` | Check a real API error end to end, using a deliberately invalid key (free) |

**Release builds** are shrunk with R8 (about 25 MB, versus about 80 MB for debug) and not debuggable. `versionCode` is the git commit count, so every release is newer than the last.

**Device tests never run on a real phone.** Gradle uninstalls the app after them, which deletes all of Daybook's data, so `scripts/test` refuses anything but the emulator. Run `./gradlew connectedDebugAndroidTest` by hand only if you know which device it will hit.

**Wireless debugging** works with the scripts too: pair once in Developer options → Wireless debugging, and `adb` reconnects whenever it's turned on. Keep it on trusted networks only, and off when not in use.

## Backlog

Bugs, features and tech debt are tracked in [BACKLOG.md](BACKLOG.md).
