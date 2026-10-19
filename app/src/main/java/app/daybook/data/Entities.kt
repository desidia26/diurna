package app.daybook.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

// Dates are "yyyy-MM-dd" and times of day "HH:mm", both local. Days roll over at midnight.

/** Shared catalog, so "Bench press" is one thing across every workout. */
@Entity(tableName = "exercise", indices = [Index(value = ["name"], unique = true)])
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Left out of suggestions. Used instead of deleting when the exercise has history. */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
)

/** A saved food with exact numbers, typed in once and logged with a tap. Flat: no sub-meals. */
@Entity(tableName = "meal", indices = [Index(value = ["name"], unique = true)])
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kcal: Int,
    @ColumnInfo(name = "protein_g") val proteinG: Int,
    @ColumnInfo(name = "carbs_g") val carbsG: Int,
    @ColumnInfo(name = "fat_g") val fatG: Int,
    /** Serving description, e.g. "1 bottle, 11 oz". */
    val note: String? = null,
)

@Entity(tableName = "workout_template", indices = [Index(value = ["name"], unique = true)])
data class WorkoutTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
)

@Entity(
    tableName = "template_exercise",
    primaryKeys = ["template_id", "position"],
    foreignKeys = [
        ForeignKey(WorkoutTemplateEntity::class, ["id"], ["template_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"]),
    ],
    indices = [Index("exercise_id")],
)
data class TemplateExerciseEntity(
    @ColumnInfo(name = "template_id") val templateId: Long,
    val position: Int,
    @ColumnInfo(name = "exercise_id") val exerciseId: Long,
)

/** weekday is ISO: 1 = Monday ... 7 = Sunday. */
@Entity(
    tableName = "routine_day",
    foreignKeys = [ForeignKey(WorkoutTemplateEntity::class, ["id"], ["template_id"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("template_id")],
)
data class RoutineDayEntity(
    @PrimaryKey val weekday: Int,
    @ColumnInfo(name = "alarm_time") val alarmTime: String,
    @ColumnInfo(name = "template_id") val templateId: Long?,
)

@Entity(tableName = "day")
data class DayEntity(
    @PrimaryKey val date: String,
    /** "HH:mm", "off", or null to follow the routine. */
    @ColumnInfo(name = "alarm_override") val alarmOverride: String? = null,
    /** The alarm actually armed for this day; frozen once the day locks. */
    @ColumnInfo(name = "scheduled_alarm") val scheduledAlarm: String? = null,
    @ColumnInfo(name = "scanned_at") val scannedAt: String? = null,
    /** "scan" or "manual". */
    @ColumnInfo(name = "morning_source") val morningSource: String? = null,
    val notes: String? = null,
    /** Morning weigh-in, lb. */
    @ColumnInfo(name = "body_weight") val bodyWeight: Double? = null,
    // Frozen when the day is finalized (after it ends), so later routine or profile edits
    // don't rewrite history. Null on days that aren't finalized yet: those use live values.
    @ColumnInfo(name = "planned_workout") val plannedWorkout: String? = null,
    @ColumnInfo(name = "target_kcal") val targetKcal: Int? = null,
    @ColumnInfo(name = "target_protein_g") val targetProteinG: Int? = null,
    @ColumnInfo(name = "target_carbs_g") val targetCarbsG: Int? = null,
    @ColumnInfo(name = "target_fat_g") val targetFatG: Int? = null,
    @ColumnInfo(defaultValue = "0") val finalized: Boolean = false,
    /** The day's step count, frozen when the day is finalized. */
    val steps: Long? = null,
    // Evening check-in, 1 (worst) to 5 (best). Stays editable after the day is finalized:
    // it's an opinion of the day, not a record of what was planned.
    val mood: Int? = null,
    val energy: Int? = null,
)

@Entity(
    tableName = "day_workout",
    foreignKeys = [ForeignKey(DayEntity::class, ["date"], ["date"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["date"], unique = true)],
)
data class DayWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val name: String,
)

@Entity(
    tableName = "logged_exercise",
    foreignKeys = [
        ForeignKey(DayWorkoutEntity::class, ["id"], ["workout_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exercise_id"]),
    ],
    indices = [Index("workout_id"), Index("exercise_id")],
)
data class LoggedExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "workout_id") val workoutId: Long,
    @ColumnInfo(name = "exercise_id") val exerciseId: Long,
    val position: Int,
)

@Entity(
    tableName = "exercise_set",
    foreignKeys = [ForeignKey(LoggedExerciseEntity::class, ["id"], ["logged_exercise_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("logged_exercise_id")],
)
data class ExerciseSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "logged_exercise_id") val loggedExerciseId: Long,
    val position: Int,
    val weight: Double? = null,
    val reps: Int? = null,
)

@Entity(
    tableName = "food_entry",
    foreignKeys = [
        ForeignKey(DayEntity::class, ["date"], ["date"], onDelete = ForeignKey.CASCADE),
        // Deleting a saved meal keeps past entries and their numbers.
        ForeignKey(MealEntity::class, ["id"], ["meal_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("date"), Index("meal_id")],
)
data class FoodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val time: String,
    val text: String,
    /** Epoch millis of when it was entered; differs from [date] when backfilled. */
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
    // AI estimate, derived from [text]. Editing the text clears these and re-estimates.
    @ColumnInfo(name = "kcal_low") val kcalLow: Int? = null,
    @ColumnInfo(name = "kcal_high") val kcalHigh: Int? = null,
    @ColumnInfo(name = "protein_g") val proteinG: Int? = null,
    @ColumnInfo(name = "carbs_g") val carbsG: Int? = null,
    @ColumnInfo(name = "fat_g") val fatG: Int? = null,
    /** What the estimate assumed, e.g. "1 cup rice, no guac". */
    @ColumnInfo(name = "estimate_note") val estimateNote: String? = null,
    @ColumnInfo(name = "estimate_model") val estimateModel: String? = null,
    /** Epoch millis; null while pending. */
    @ColumnInfo(name = "estimated_at") val estimatedAt: Long? = null,
    /** Why the last attempt failed, shown on the entry; cleared on retry. */
    @ColumnInfo(name = "estimate_error") val estimateError: String? = null,
    /** "ai" (estimated from the text) or "meal" (exact numbers copied from a saved meal). */
    @ColumnInfo(defaultValue = "'ai'") val source: String = SOURCE_AI,
    /** The saved meal this was logged from; null for AI entries or if the meal was deleted. */
    @ColumnInfo(name = "meal_id") val mealId: Long? = null,
    /** Meal entries' numbers are the meal's × servings, copied at log time. */
    @ColumnInfo(defaultValue = "1.0") val servings: Double = 1.0,
) {
    val isMeal: Boolean get() = source == SOURCE_MEAL

    companion object {
        const val SOURCE_AI = "ai"
        const val SOURCE_MEAL = "meal"
    }

    val kcalMid: Int? get() = if (kcalLow != null && kcalHigh != null) (kcalLow + kcalHigh) / 2 else null
}

// ---- Read models ----

data class LoggedExerciseWithSets(
    @Embedded val logged: LoggedExerciseEntity,
    @Relation(parentColumn = "exercise_id", entityColumn = "id") val exercise: ExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "logged_exercise_id") val sets: List<ExerciseSetEntity>,
)

data class WorkoutWithExercises(
    @Embedded val workout: DayWorkoutEntity,
    @Relation(entity = LoggedExerciseEntity::class, parentColumn = "id", entityColumn = "workout_id")
    val exercises: List<LoggedExerciseWithSets>,
)

data class TemplateExerciseName(val templateId: Long, val position: Int, val name: String)

data class LastTimeRef(val loggedExerciseId: Long, val date: String)

data class DateName(val date: String, val name: String)

data class DateKcal(val date: String, val kcal: Int)

data class CatalogExercise(val id: Long, val name: String, val hidden: Boolean, val logged: Int, val inRoutine: Int) {
    val inUse: Boolean get() = logged > 0 || inRoutine > 0
}
