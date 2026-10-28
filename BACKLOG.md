# Backlog

One list for features, bugs and tech debt. Add new items at the bottom of their section with the next free number. Strike through finished items and note when they shipped, rather than deleting them.

**Priority:** P1 = do next · P2 = soon · P3 = someday

## Bugs

| # | P | Item | Notes |
|---|---|---|---|
| — | Grok (xAI) as an alternative to Claude for food estimates, chosen in Setup | 2026-10-25 |
| B1 | P2 | "Last time" on today's workout doesn't refresh after editing an older workout | Only re-queried when today's workout changes. Workaround: switch days. |
| B2 | P3 | Scheduler records `scheduled_alarm` even when the system refused to arm it | `AlarmScheduler.reschedule` marks the day before `arm()`; with exact alarms denied, the frozen time is one that never rang. |
| B3 | P3 | Alarm screen shows the "wrong code" hint briefly even for the right code | The screen closes right after, so it's barely visible. |

## Data and history

| # | P | Item | Notes |
|---|---|---|---|
| D3 | P1 | Log alarm events: fired at, scanned at, timed out | Tells "rang and ignored" apart from "never rang", and doubles as a reliability record for the phone. |
| D6 | P3 | Units setting (lb/kg) | Weight is assumed lb. |

## Logging friction

| # | P | Item | Notes |
|---|---|---|---|
| L2 | P3 | Rename a saved workout in Routine | The list editor shipped; renaming is the remaining piece. |
| L3 | P2 | Rename a logged workout | Blank workouts are all called "Workout". |
| L4 | P3 | Suggest next weight from last time | E.g. hit every set last time → suggest +5 lb. |
| L5 | P3 | Merge duplicate exercises | e.g. "db bench press" and "Dumbbell bench press" both with history: move one's history onto the other. |

## Features

| # | P | Item | Notes |
|---|---|---|---|
| F2 | P3 | Cross-day insights | E.g. "you eat more on days you skip the gym", "protein is low on rest days". |
| F3 | P3 | Home-screen widget with quick actions | Log food or start today's workout without opening the app. |
| F4 | P3 | Dark-morning handling | In winter the alarm can go off before sunrise; maybe a separate "outside after sunrise" goal. |
| F6 | P3 | Metrics: comparisons card | Energy on workout vs rest days, mood on on-time vs late mornings and on step-goal days, weight vs calories; shown only once each side has 5+ days. Wireframed ("Comparisons"). Overlaps F2. |
| F7 | P3 | Metrics: tap a chart point to see that day | Shows the date and value, and opens the day. Wireframed ("States", S4). |
| F8 | P3 | Metrics: on-time mornings by week | Left over from F5. |
| F9 | P3 | Cardio calories in the day's targets | Add a logged piece's Cal to the day's calorie target (or show it beside it). Left out of the first cardio release on purpose: machine calorie figures run high. |
| F10 | P3 | Import cardio from Health Connect | If a machine or app (Ergatta, Peloton, a watch) writes sessions there, log them without a photo. Check what the phone actually writes first; steps had nothing. |

## Alarm and lock screen

| # | P | Item | Notes |
|---|---|---|---|
| A1 | P1 | Verify on the Pixel 9a: alarm through reboot and deep sleep | Only tested on the emulator. `adb reboot` and `adb shell dumpsys deviceidle force-idle` with an alarm pending. |
| A2 | P1 | ~~Verify the lock-screen summary's position on the Pixel 9a~~ | ~~Check it doesn't collide with the clock or shortcuts; move it if it does.~~ Shipped 2026-10-26: the fingerprint icon, then notifications, covered it; see Done. |
| A3 | P3 | Restore the previous lock wallpaper when turning the summary off | Currently falls back to the home wallpaper. |

## Tech debt

| # | P | Item | Notes |
|---|---|---|---|
| T2 | P2 | Compose UI tests | Logic is covered by unit and instrumented tests; the screens aren't. |
| T4 | P3 | Upgrade the rest of the libraries together | Compose moved to BOM 2026.06.00 (2026-10-22) to fix a crash; Kotlin, AGP, Room, CameraX and the rest are still the mid-2025 set. |
| T5 | P3 | Debug APK is ~82 MB | Mostly the Anthropic SDK's HTTP dependencies plus ML Kit. Release builds are shrunk with R8 to ~25 MB, so this only affects debug installs. |

## Done

| # | Item | Shipped |
|---|---|---|
| AI prompt eval suite (Grok/Claude/Ollama smoke) + CI | ai-evals |
| — | Keyboard covers text fields at the bottom of the screen | 2026-10-12 |
| — | Test alarm button gives no feedback | 2026-10-12 |
| — | "Log a workout anyway" did nothing with no saved workouts; added "Blank workout" | 2026-10-12 |
| — | Fast typing in weight/reps could drop keystrokes | 2026-10-12 |
| — | No way back to a ringing alarm after swiping its notification away | 2026-10-12 |
| — | "bench press" and "Bench press" were different exercises | 2026-10-12 |
| — | Today said "not up yet" even long after the alarm; now upcoming → not scanned → missed | 2026-10-12 |
| — | Exercise catalog seeded with ~55 common lifts; autocomplete when adding exercises | 2026-10-12 |
| — | Routine workouts edited as a list (add with autocomplete, reorder, remove) instead of comma text | 2026-10-12 |
| — | Keyboard still covered the bottom of lists by the tab bar's height | 2026-10-12 |
| — | Day screen cards fold to a one-line summary (tap the header) | 2026-10-13 |
| D1 | Each finished day's alarm is frozen (day finalizer) | 2026-10-13 |
| D2 | Each finished day's planned workout is frozen | 2026-10-13 |
| D5 | Body weight per day (Morning card) | 2026-10-13 |
| L1 | Edit food entries (text and time); text edits re-estimate | 2026-10-13 |
| F1 | AI calorie and macro estimates per food entry, with daily targets from a profile in Setup | 2026-10-13 |
| T1 | First database migration (v1→v2) with a migration test | 2026-10-13 |
| T3 | One ViewModel per screen | 2026-10-13 |
| — | Enter in the food box added a new line instead of logging the entry | 2026-10-13 |
| — | Estimate errors: friendly message per case (spend limit, credit, key, bad request), retries capped at 8, unexpected errors shown instead of looping, offline state shown | 2026-10-13 |
| A4 | Live-tested API errors: an invalid key returns 401 with the full JSON body in the SDK exception message, and lands on "API key was rejected". Opt-in test: `LIVE_API=1 ./gradlew testDebugUnitTest --tests '*LiveApiErrorTest*'` | 2026-10-13 |
| — | Saved meals (Setup → Library → Meals): exact numbers, tap-to-log chips in the food box, servings, "Save as meal" from AI entries. Migration v3 with test | 2026-10-13 |
| — | Exercise library (Setup → Library → Exercises): add, rename (history follows), hide, delete if unused | 2026-10-13 |
| — | Food box suggestions stay visible above the keyboard (shared keep-visible-while-typing) | 2026-10-13 |
| — | Bigger, rounded chevron icons for date arrows, collapse arrows and the Library back button (were small text glyphs) | 2026-10-14 |
| — | Routine: workouts fold to a one-line summary; "New workout" moved to a header button with a name dialog, so exercise boxes are clearly inside their workout | 2026-10-16 |
| — | Steps groundwork: Morning card row with progress, Week view, lock-screen line (15-minute refresh), goal in Setup (default 10,000), frozen per day; migration v4. Health Connect was tried and removed: nothing on the phone writes steps into it | 2026-10-16 |
| F6 | Steps counted with the phone's own step sensor: sampled every 15 minutes, at 23:59 and at boot; reboot- and midnight-safe (`StepMath`) | 2026-10-16 |
| — | Lock screen flashed every few seconds: redraw read steps via a sensor look, whose writes triggered another redraw. Redraws now use the saved count, settings flows only emit real changes, and identical pictures are never re-set | 2026-10-16 |
| — | Self-backups: the app writes a consistent copy of its database and settings daily (keeps 14) and on request to its shared-storage folder; scripts/backup-db pulls those, so it works with release builds too. Setup → Backups shows the last one and has "Back up now" | 2026-10-16 |
| — | Evening check-in: mood and energy (1–5, a word per step) on each day, editable any time; a 9pm reminder that skips rated days and clears itself once both are in. Database v5 | 2026-10-16 |
| — | Barcode scanning for food: package barcodes (EAN/UPC) looked up on Open Food Facts, checked in the meal form, saved to the library with the barcode; rescans go straight to servings and work offline. Responses treated as untrusted (HTTPS only, no redirects, size and time caps, sanity limits on numbers, plain short text). Database v7 | 2026-10-20 |
| — | Exercise logging usability: Enter walks weight → reps → next set across exercises (✓ on the last field closes the keyboard); Enter on an empty field accepts the grey hint (weight follows the set above); tapping a filled field selects it; "+ Set" focuses the new row; per-exercise "done/total" chip; ⋮ menu to move up/down or remove (confirm when it has data); bigger × on sets | 2026-10-20 |
| — | Crash: "launchAnimation called when previous animation was running" (three times on the phone) when the keyboard shrank the screen while a scroll-into-view was running, a Compose 1.8 bug. Fixed by moving to Compose BOM 2026.06.00 (foundation 1.11), whose rewritten code path no longer has the crashing call; outlined buttons kept purple after Material 3 1.4 made them grey | 2026-10-22 |
| — | Tap an exercise name to fold it to one line of its sets (Enter skips folded exercises); exercises with every set done show their name in green | 2026-10-23 |
| F5 | Metrics tab: weight (daily dots and 7-day average), mood and energy (7-day averages past 4 weeks), weekly training volume, workouts and PRs, and the most-logged lifts by estimated 1RM; each lift opens a screen with e1RM or volume per session, records and session history. Ranges 4w/12w/1y/All, remembered | 2026-10-24 |
| — | Meal photos: attach a photo to a food entry (camera or photo picker, no storage permission) from the camera icon next to the food box; the text stays the entry and the photo is sent with it to Claude or Grok for the estimate. Thumbnail on the entry (tap for a larger view); add, replace or remove in the edit dialog. Stored downscaled (1568 px JPEG) in app-private storage, left out of cloud backup, mirrored in self-backups. Database v8 | 2026-10-25 |
| A2 | Lock screen: the fingerprint sensor's icon covered the summary, the nutrition line ran off the right edge, and then notifications covered it. Split around the sensor and centred: the date and the day's lines end just above the sensor, growing upward as lines wrap, so a typical day starts around the middle of the screen, below a notification or two; the week's dots sit below it, above the shortcuts. Lines wrap at the " · " between facts and the text shrinks only when a day won't fit. Drawn allowing for the 1.1x zoom the system shows wallpapers at. The sensor's position comes from the platform when the maker sets it, else a band measured on the Pixel; there's no API for how far notifications reach, so Setup's Lock screen card has a Position slider (raise from Auto, up to 15% of the screen), a Text size slider (80–125%), Reset to Auto and a live preview | 2026-10-26 |
| — | Cardio for any activity, off until wanted: Setup → Library → "Cardio exercises" turns it on (off keeps cardio out of suggestions and Library; nothing changes for people who only lift, down to the lock-screen and Metrics text). A Cardio kind of exercise with a style that sets what it asks for and how pace reads: Rowing (/500m, power), Run or walk (/km or /mi), Cycling (km/h or mph, power), Time and calories (elliptical, stair climber). Seeded: Treadmill, Run, Walk, Stationary bike, Rowing machine, Elliptical, Stair climber. Logged as pieces, every field optional (distance, time, pace or speed, best split, avg power, Cal, avg heart rate); done once it has a distance or a time. "Log from photo" reads a treadmill, bike, rower or elliptical summary screen with Claude or Grok, then shows the numbers to check before saving. Metrics shows a Cardio card only once cardio is logged (this week's distance, or minutes, a weekly chart, PRs) and a per-exercise screen of distance and pace/speed, power, or time and calories, with records; PRs compare sessions of the same length, the first one being the baseline. Lock screen: "Rowing machine · 3,260 m · 2:10/500m", "Run · 5.0 km · 5:12/km", "Elliptical · 30 min · 250 kcal"; mixed days add "· 3.1 mi cardio". Cardio stays out of lifting volume and e1RM. Database v9 | 2026-10-26 |
| — | Housekeeping: Dependabot version updates (monthly, grouped, 7-day cooldown) for Gradle and GitHub Actions; the full resolved dependency tree submitted on each push to `main`, so CVE alerts cover transitive dependencies; `.gitattributes` for line endings; `scripts/release` pushes its `release-N` tag; README and CLAUDE.md no longer describe a one-person app | 2026-10-27 |
| — | "View metrics" in a Day exercise's ⋮ menu opens that exercise's Metrics screen (lift or cardio); back returns to Day. Shown once the exercise has a session to chart | 2026-10-27 |
| — | What's new: a dialog on the first launcher open after an update lists new `Changelog.kt` entries; Setup shows recent ones and all of them on a page; `scripts/release` warns when the app changed without an entry | 2026-10-27 |
| — | Metrics: Macros card — stacked kcal from protein/carbs/fat (P×4+C×4+F×9), kcal/% toggle (remembered), dashed target line in kcal mode; hidden until any food is estimated; daily bars on 4w, week averages longer. No DB migration | 2026-10-27 |
