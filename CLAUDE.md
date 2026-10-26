# Diurna

Personal Android app (Kotlin, Jetpack Compose, Room) for one person's phone: a porch-QR alarm, food logging with AI estimates and barcodes, workouts from a weekly routine (lifting, plus optional cardio), steps and an evening check-in. Read [README.md](README.md) for the mission, principles and architecture map, and [BACKLOG.md](BACKLOG.md) for the work list.

## Commands

- `scripts/test`: unit tests, plus device tests on a running emulator (`scripts/emulator --headless` starts one)
- `scripts/install`: debug build onto the connected device
- `scripts/release`: tests, release build, backup, install as an update, data check, alarm check, tag
- `scripts/backup-db`, `scripts/logs`, `scripts/screenshot`, `scripts/alarm-check`, `scripts/demo-data` (emulator only: replaces app data with made-up history)
- `./gradlew assembleDebug` / `testDebugUnitTest` for a build or unit tests without a device

Check a build by its exit code, not by filtered output.

## Hard rules

- **Never run device tests on the real phone.** `connectedDebugAndroidTest` uninstalls the app afterwards and wipes its data. Use `scripts/test`, which only targets the emulator.
- **Install to the phone only through `scripts/release`.** It backs up first and checks that no rows were lost. Don't uninstall the app, clear its data, reboot the phone or change its settings without asking.
- **Never change `applicationId app.daybook` or the signing key**. The app was renamed from Daybook to Diurna; the code package is `app.diurna`, but the app id, `daybook.db` and backup file names keep the old name on purpose (`signing/`, git-ignored). Either change makes in-place updates impossible.
- **Never commit secrets or personal data:** API keys, `signing/`, `local.properties`, `backups/`, databases. The Claude API key is entered in the app and stored encrypted on the phone; never put one in code, tests or chat.
- **Every schema change ships with a migration and a `MigrationTest` case.** Prefer `AutoMigration`; data-only changes use a `Migration`. Never a destructive fallback.

## Conventions

- Pure rules live in `domain/` as plain Kotlin with unit tests. Repositories own all writes; screens go through one ViewModel each.
- Dates are `yyyy-MM-dd` and times `HH:mm`, both local text. Days roll over at midnight. Finished days are frozen by `DayFinalizer`, and history is never rewritten by later edits.
- Data from outside the app (Open Food Facts, the Claude API) is untrusted: validate, cap and parse it into plain values.
- Cardio (`exercise.kind = 'cardio'`) shares `exercise_set` with lifting but never counts toward volume, e1RM or weight/reps hints; anything that sums sets must check the kind. `split_s` is always seconds per 500 m; the exercise's `cardio_style` (`Cardio.Style`) only changes how it's typed and shown (/500m, /km, /mi, km/h, mph, none). Cardio is opt-in (Setup → Library): with it off, or with no cardio logged, every lifting screen and line must read exactly as before; keep rowing-specific wording to the rowing style.
- Match the surrounding code's comment density and naming. Comments explain why, not what.
- UI is dark only: grey, black, purple, white.
- New features start with a discussion of the data shape before code, unless asked to just build.
- When something ships, add it to BACKLOG.md's Done table, and keep README's "How it works" table current.
