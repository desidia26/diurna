package app.daybook.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Common lifts, so autocomplete has something to offer before you've typed them yourself. Names put
 * the equipment first ("Machine chest press", "Smith squat"); autocomplete matches anywhere in a name.
 */
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
    "Bulgarian split squat", "Lunge", "Step-up", "Leg extension", "Seated leg curl", "Hip thrust", "Calf raise",
    // Arms
    "Barbell curl", "Dumbbell curl", "Hammer curl", "Preacher curl", "Cable curl",
    "Triceps pushdown", "Skull crusher", "Overhead triceps extension", "Close-grip bench press",
    // Core
    "Plank", "Hanging leg raise", "Cable crunch", "Ab wheel rollout", "Russian twist", "Pallof press",
    // Carries and conditioning
    "Farmer carry", "Kettlebell swing", "Sled push",

    // Planet Fitness lineup (pfguides.com/planet-fitness-equipment-list). Some clubs lack some of these;
    // hide what yours doesn't have in Setup → Library.
    // Machines, upper body
    "Machine chest press", "Machine pec fly", "Machine rear delt fly", "Machine shoulder press",
    "Machine seated row", "Assisted pull-up", "Assisted dip", "Machine biceps curl",
    "Machine triceps extension", "Machine lateral raise",
    // Machines, lower body
    "Lying leg curl", "Hip abduction", "Hip adduction", "Machine calf raise", "Machine glute kickback", "Hack squat",
    // Machines, core
    "Machine ab crunch", "Machine torso rotation", "Captain's chair leg raise",
    // Smith machine
    "Smith squat", "Smith bench press", "Smith incline press", "Smith shoulder press", "Smith row",
    "Smith calf raise", "Smith split squat",
    // Cables
    "Cable lateral raise", "Cable overhead triceps extension", "Cable glute kickback", "Cable woodchop",
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
