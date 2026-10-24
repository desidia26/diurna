"""Fills a freshly created Diurna database with made-up history, for screenshots and trying things out.

Run by scripts/demo-data; not meant to be run on its own.
    python3 demo-data.py <daybook.db> <today yyyy-MM-dd> [weeks]

Writes the routine, three workout templates, and one row per day back from yesterday: morning scan,
weight, steps, mood and energy, food, and on workout days the lifted sets. Past days are written
already finalized, as DayFinalizer would leave them. Today gets only a weigh-in and a porch scan.
Seeded, so every run with the same date gives the same data.
"""
import random
import sqlite3
import sys
from datetime import date, datetime, timedelta

db_path, today_text = sys.argv[1], sys.argv[2]
weeks = int(sys.argv[3]) if len(sys.argv) > 3 else 12
today = date.fromisoformat(today_text)
# From a Monday, so the first week of volume is a whole one.
first = today - timedelta(weeks=weeks)
first -= timedelta(days=first.weekday())
rng = random.Random(today_text)

# weekday (ISO) -> (alarm, template or None)
ROUTINE = {
    1: ("06:30", "Push"), 2: ("06:30", None), 3: ("06:30", "Pull"), 4: ("06:30", None),
    5: ("06:30", "Legs"), 6: ("07:30", None), 7: ("07:30", None),
}
# Exercise -> (starting weight lb, weekly gain lb, sets, reps). Names must be in the seed catalog.
TEMPLATES = {
    "Push": {"Bench press": (155, 2.5, 3, 6), "Overhead press": (95, 1.25, 3, 6),
             "Incline dumbbell press": (50, 1.25, 3, 10), "Triceps pushdown": (50, 1.25, 3, 12)},
    "Pull": {"Barbell row": (135, 2.5, 3, 8), "Lat pulldown": (120, 2.5, 3, 10),
             "Dumbbell curl": (25, 0.6, 3, 12), "Face pull": (40, 1.25, 3, 12)},
    "Legs": {"Squat": (185, 5, 3, 5), "Romanian deadlift": (155, 2.5, 3, 8),
             "Leg press": (270, 5, 3, 10), "Calf raise": (135, 2.5, 3, 12)},
}
TARGETS = (2300, 180, 230, 75)  # kcal, protein, carbs, fat
# (time, text, kcal low, kcal high, protein, carbs, fat, assumption)
MEALS = [
    [("07:15", "oatmeal with blueberries and a scoop of whey", 420, 500, 32, 62, 8, "1 cup dry oats, 1 scoop whey"),
     ("07:10", "3 eggs scrambled, 2 toast, coffee with milk", 480, 580, 28, 34, 26, "whole-wheat toast, 2 tbsp milk"),
     ("07:20", "greek yogurt, granola, banana", 430, 520, 30, 68, 9, "1 cup 2% yogurt, 1/2 cup granola")],
    [("12:30", "chicken burrito bowl, no sour cream", 650, 800, 48, 78, 18, "1 cup rice, black beans, no guac"),
     ("12:45", "turkey sandwich and an apple", 520, 620, 36, 64, 14, "6 oz deli turkey, whole-wheat bread"),
     ("13:00", "salmon salad with quinoa", 560, 680, 40, 42, 26, "5 oz salmon, 1/2 cup quinoa, vinaigrette")],
    [("18:45", "steak, baked potato, green beans", 720, 880, 52, 58, 30, "8 oz sirloin, butter on the potato"),
     ("19:00", "spaghetti with meat sauce", 680, 840, 38, 92, 20, "2 cups pasta, lean beef"),
     ("18:30", "stir fry chicken with vegetables and rice", 620, 760, 46, 80, 14, "1.5 cups rice, 1 tbsp oil")],
    [("15:30", "protein shake", 160, 200, 30, 6, 3, "1 scoop whey with water"),
     ("21:00", "2 squares dark chocolate", 100, 130, 2, 10, 8, "70% cocoa, ~20 g"),
     ("16:00", "apple and peanut butter", 250, 310, 7, 28, 16, "2 tbsp peanut butter")],
]

con = sqlite3.connect(db_path)
con.execute("PRAGMA foreign_keys = ON")
cur = con.cursor()

if cur.execute("SELECT COUNT(*) FROM day").fetchone()[0]:
    sys.exit("error: the database already has days in it; demo data goes into a fresh one")


def exercise_id(name):
    row = cur.execute("SELECT id FROM exercise WHERE name = ? COLLATE NOCASE", (name,)).fetchone()
    if row is None:
        sys.exit(f"error: '{name}' isn't in the exercise catalog")
    return row[0]


template_ids = {}
for name, exercises in TEMPLATES.items():
    cur.execute("INSERT INTO workout_template (name) VALUES (?)", (name,))
    template_ids[name] = cur.lastrowid
    for pos, ex in enumerate(exercises):
        cur.execute("INSERT INTO template_exercise (template_id, position, exercise_id) VALUES (?, ?, ?)",
                    (template_ids[name], pos, exercise_id(ex)))
for weekday, (alarm, template) in ROUTINE.items():
    cur.execute("UPDATE routine_day SET alarm_time = ?, template_id = ? WHERE weekday = ?",
                (alarm, template_ids.get(template), weekday))


def clock(base, minutes):
    t = datetime.combine(today, datetime.strptime(base, "%H:%M").time()) + timedelta(minutes=minutes)
    return t.strftime("%H:%M")


def millis(d, hhmm):
    return int(datetime.combine(d, datetime.strptime(hhmm, "%H:%M").time()).timestamp() * 1000)


def log_workout(d, template, week):
    cur.execute("INSERT INTO day_workout (date, name) VALUES (?, ?)", (d.isoformat(), template))
    workout_id = cur.lastrowid
    for pos, (ex, (start, gain, sets, reps)) in enumerate(TEMPLATES[template].items()):
        cur.execute("INSERT INTO logged_exercise (workout_id, exercise_id, position) VALUES (?, ?, ?)",
                    (workout_id, exercise_id(ex), pos))
        logged_id = cur.lastrowid
        # Steady progress with a deload every fourth week, rounded to what the plates allow.
        step = 2.5 if start < 100 else 5
        weight = round((start + gain * week * (0.9 if week % 4 == 3 else 1)) / step) * step
        for s in range(sets):
            done = reps if rng.random() > 0.15 * (s + 1) else reps - rng.randint(1, 2)
            cur.execute("INSERT INTO exercise_set (logged_exercise_id, position, weight, reps) VALUES (?, ?, ?, ?)",
                        (logged_id, s, weight, done))


def log_food(d):
    for slot, options in enumerate(MEALS):
        if slot == 3 and rng.random() < 0.4:
            continue
        time, text, low, high, p, c, f, note = rng.choice(options)
        cur.execute(
            "INSERT INTO food_entry (date, time, text, logged_at, kcal_low, kcal_high, protein_g, carbs_g, fat_g,"
            " estimate_note, estimate_model, estimated_at, source, servings)"
            " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'demo', ?, 'ai', 1.0)",
            (d.isoformat(), time, text, millis(d, time), low, high, p, c, f, note, millis(d, time) + 4000))


weight = 191.0
d = first
while d < today:
    weekday = d.isoweekday()
    week = (d - first).days // 7
    alarm, template = ROUTINE[weekday]
    # A few days with the alarm turned off ahead of time, a few late or manual mornings.
    roll = rng.random()
    if weekday == 7 and roll < 0.25:
        override, scanned, source = "off", None, None
    elif roll < 0.06:
        override, scanned, source = None, clock(alarm, rng.randint(25, 50)), "scan"
    elif roll < 0.09:
        override, scanned, source = None, clock(alarm, rng.randint(5, 15)), "manual"
    else:
        override, scanned, source = None, clock(alarm, rng.randint(1, 9)), "scan"
    scheduled = None if override == "off" else alarm

    # About 0.6 lb a week down, with day-to-day water noise and some skipped weigh-ins.
    weight -= 0.09
    body = round(weight + rng.gauss(0, 0.7), 1) if rng.random() < 0.85 else None

    lifted = template is not None and rng.random() < 0.92
    steps = rng.randint(5500, 9500) + (2500 if weekday in (6, 7) else 0)
    rated = rng.random() < 0.85
    mood = max(1, min(5, round(rng.gauss(3.6, 0.8)))) if rated else None
    energy = max(1, min(5, round(rng.gauss(3.9 if lifted else 3.2, 0.8)))) if rated else None

    cur.execute(
        "INSERT INTO day (date, alarm_override, scheduled_alarm, scanned_at, morning_source, body_weight,"
        " planned_workout, target_kcal, target_protein_g, target_carbs_g, target_fat_g, finalized, steps, mood, energy)"
        " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)",
        (d.isoformat(), override, scheduled, scanned, source, body, template, *TARGETS, steps, mood, energy))
    if lifted:
        log_workout(d, template, week)
    log_food(d)
    d += timedelta(days=1)

alarm, _ = ROUTINE[today.isoweekday()]
cur.execute("INSERT INTO day (date, scanned_at, morning_source, body_weight) VALUES (?, ?, 'scan', ?)",
            (today.isoformat(), clock(alarm, 3), round(weight - 0.1, 1)))

con.commit()
con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
con.close()
print(f"demo data: {weeks} weeks from {first} to {today}")
