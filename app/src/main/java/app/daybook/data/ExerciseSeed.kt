package app.daybook.data

import androidx.sqlite.db.SupportSQLiteDatabase

/** Common lifts, so autocomplete has something to offer before you've typed them yourself. */
val SEED_EXERCISES = listOf(
    // Chest
    "Bench press", "Incline bench press", "Dumbbell bench press", "Incline dumbbell press",
    "Chest fly", "Cable fly", "Push-up", "Dips",
    // Shoulders
    "Overhead press", "Dumbbell shoulder press", "Arnold press", "Lateral raise",
    "Rear delt fly", "Face pull", "Upright row",
    // Back
    "Deadlift", "Pull-up", "Chin-up", "Lat pulldown", "Barbell row", "Dumbbell row",
    "Seated cable row", "T-bar row", "Shrug", "Back extension",
    // Legs
    "Squat", "Front squat", "Goblet squat", "Leg press", "Romanian deadlift",
    "Bulgarian split squat", "Lunge", "Step-up", "Leg extension", "Leg curl", "Hip thrust", "Calf raise",
    // Arms
    "Barbell curl", "Dumbbell curl", "Hammer curl", "Preacher curl", "Cable curl",
    "Triceps pushdown", "Skull crusher", "Overhead triceps extension", "Close-grip bench press",
    // Core
    "Plank", "Hanging leg raise", "Cable crunch", "Ab wheel rollout", "Russian twist", "Pallof press",
    // Carries and conditioning
    "Farmer carry", "Kettlebell swing", "Sled push",
)

/**
 * Adds any seed exercise that isn't in the catalog yet, ignoring case so an existing
 * "bench press" isn't duplicated as "Bench press". Safe to run on every open.
 */
fun seedExercises(db: SupportSQLiteDatabase) {
    SEED_EXERCISES.forEach { name ->
        db.execSQL(
            "INSERT INTO exercise (name) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM exercise WHERE name = ? COLLATE NOCASE)",
            arrayOf<Any>(name, name),
        )
    }
}
