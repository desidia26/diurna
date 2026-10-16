# Backlog

One list for features, bugs and tech debt. Add new items at the bottom of their section with the next free number. Strike through finished items and note when they shipped, rather than deleting them.

**Priority:** P1 = do next · P2 = soon · P3 = someday

## Bugs

| # | P | Item | Notes |
|---|---|---|---|
| B1 | P2 | "Last time" on today's workout doesn't refresh after editing an older workout | Only re-queried when today's workout changes. Workaround: switch days. |
| B2 | P3 | Scheduler records `scheduled_alarm` even when the system refused to arm it | `AlarmScheduler.reschedule` marks the day before `arm()`; with exact alarms denied, the frozen time is one that never rang. |
| B3 | P3 | Alarm screen shows the "wrong code" hint briefly even for the right code | The screen closes right after, so it's barely visible. |

## Data and history

| # | P | Item | Notes |
|---|---|---|---|
| D3 | P1 | Log alarm events: fired at, scanned at, timed out | Tells "rang and ignored" apart from "never rang", and doubles as a reliability record for the phone. |
| D4 | P2 | Export everything (JSON or CSV) | Data lives only on the phone; uninstalling or losing it loses the history. |
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
| F5 | P3 | Progress charts | Per-exercise weight over time, on-time mornings by week. |
| F6 | P1 | Count steps with the phone's step sensor | Phase 2 of steps: Daybook samples the hardware step counter itself (no other app needed) and plugs in behind `StepSource`. Needs care around reboots and midnight. |

## Alarm and lock screen

| # | P | Item | Notes |
|---|---|---|---|
| A1 | P1 | Verify on the Pixel 9a: alarm through reboot and deep sleep | Only tested on the emulator. `adb reboot` and `adb shell dumpsys deviceidle force-idle` with an alarm pending. |
| A2 | P1 | Verify the lock-screen summary's position on the Pixel 9a | Check it doesn't collide with the clock or shortcuts; move it if it does. |
| A3 | P3 | Restore the previous lock wallpaper when turning the summary off | Currently falls back to the home wallpaper. |

## Tech debt

| # | P | Item | Notes |
|---|---|---|---|
| T2 | P2 | Compose UI tests | Logic is covered by unit and instrumented tests; the screens aren't. |
| T4 | P3 | Upgrade libraries together | Pinned to a mid-2025 set. |
| T5 | P3 | Debug APK is ~82 MB | Mostly the Anthropic SDK's HTTP dependencies plus ML Kit. Fine for sideloading; shrink with R8 if it ever matters. |

## Done

| # | Item | Shipped |
|---|---|---|
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
