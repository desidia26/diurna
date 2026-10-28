package app.diurna.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.diurna.domain.Cardio

// Dates are "yyyy-MM-dd" and times of day "HH:mm", both local. Days roll over at midnight.

/** Shared catalog, so "Bench press" is one thing across every workout. */
@Entity(tableName = "exercise", indices = [Index(value = ["name"], unique = true)])
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Left out of suggestions. Used instead of deleting when the exercise has history. */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /**
     * [KIND_STRENGTH] sets are weight × reps; [KIND_CARDIO] sets are a piece of distance, time and
     * so on (a run, a ride, a row). Only changeable while nothing is logged, so history never mixes the two.
     */
    @ColumnInfo(defaultValue = "'strength'") val kind: String = KIND_STRENGTH,
    /**
     * For cardio, which fields it asks for and how pace reads: a [app.diurna.domain.Cardio.Style]
     * key ("500m", "km", "mi", "kmh", "mph", "time"). Null for strength. Changeable any time,
     * since stored values don't depend on it.
     */
    @ColumnInfo(name = "cardio_style") val cardioStyle: String? = null,
) {
    val isCardio: Boolean get() = kind == KIND_CARDIO
    val style: Cardio.Style get() = Cardio.Style.of(cardioStyle)

    companion object {
        const val KIND_STRENGTH = "strength"
        const val KIND_CARDIO = "cardio"
    }
}

/**
 * A saved food with exact numbers. [KIND_SIMPLE] is a flat P/C/F entry; [KIND_RECIPE] has
 * [RecipeIngredientEntity] rows whose snapshots sum into per-serving totals; [KIND_RESTAURANT]
 * is a dish from a restaurant with optional [nutritionSource] (published vs estimate).
 */
@Entity(tableName = "meal", indices = [Index(value = ["name"], unique = true), Index(value = ["barcode"], unique = true), Index(value = ["kind"])])
data class MealEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kcal: Int,
    @ColumnInfo(name = "protein_g") val proteinG: Int,
    @ColumnInfo(name = "carbs_g") val carbsG: Int,
    @ColumnInfo(name = "fat_g") val fatG: Int,
    /** Serving description, e.g. "1 bottle, 11 oz". */
    val note: String? = null,
    /** Package barcode for meals added by scanning, normalized by [normalizeBarcode]. */
    val barcode: String? = null,
    /**
     * Open Food Facts source snapshot JSON ([OffSnapshot.encode]), when this meal was scanned.
     * Public product data only; see [OffSnapshot]. Null when never looked up or added manually.
     */
    @ColumnInfo(name = "off_snapshot_json") val offSnapshotJson: String? = null,
    /** [KIND_SIMPLE], [KIND_RECIPE], or [KIND_RESTAURANT]. Existing rows migrate to simple. */
    @ColumnInfo(defaultValue = "'simple'") val kind: String = KIND_SIMPLE,
    /** How many servings the recipe batch makes. Null/1 for simple meals. */
    @ColumnInfo(name = "servings_yield") val servingsYield: Double? = null,
    /** Optional cooked batch weight in grams; enables logging by grams eaten. */
    @ColumnInfo(name = "cooked_weight_g") val cookedWeightG: Double? = null,
    /** Restaurant name for [KIND_RESTAURANT] dishes (e.g. "Chipotle"). */
    @ColumnInfo(name = "restaurant_name") val restaurantName: String? = null,
    /**
     * For restaurant meals: [SOURCE_PUBLISHED] (chain nutrition page / labeled) or
     * [SOURCE_ESTIMATE] (menu photo / AI guess). Null for non-restaurant.
     */
    @ColumnInfo(name = "nutrition_source") val nutritionSource: String? = null,
    // Detailed nutrition (nullable = unknown). Opt-in in Setup; null is distinct from 0.
    @ColumnInfo(name = "fiber_g") val fiberG: Int? = null,
    @ColumnInfo(name = "soluble_fiber_g") val solubleFiberG: Int? = null,
    @ColumnInfo(name = "insoluble_fiber_g") val insolubleFiberG: Int? = null,
    @ColumnInfo(name = "sugars_g") val sugarsG: Int? = null,
    @ColumnInfo(name = "added_sugars_g") val addedSugarsG: Int? = null,
    @ColumnInfo(name = "starch_g") val starchG: Int? = null,
    @ColumnInfo(name = "sat_fat_g") val satFatG: Int? = null,
    @ColumnInfo(name = "mono_fat_g") val monoFatG: Int? = null,
    @ColumnInfo(name = "poly_fat_g") val polyFatG: Int? = null,
    @ColumnInfo(name = "trans_fat_g") val transFatG: Int? = null,
    @ColumnInfo(name = "cholesterol_mg") val cholesterolMg: Int? = null,
    @ColumnInfo(name = "omega3_g") val omega3G: Int? = null,
    @ColumnInfo(name = "omega6_g") val omega6G: Int? = null,
    @ColumnInfo(name = "omega9_g") val omega9G: Int? = null,
    // Amino acids (mg). Nullable = unknown. Filled from USDA FDC or manual entry.
    @ColumnInfo(name = "his_mg") val hisMg: Int? = null,
    @ColumnInfo(name = "ile_mg") val ileMg: Int? = null,
    @ColumnInfo(name = "leu_mg") val leuMg: Int? = null,
    @ColumnInfo(name = "lys_mg") val lysMg: Int? = null,
    @ColumnInfo(name = "met_mg") val metMg: Int? = null,
    @ColumnInfo(name = "phe_mg") val pheMg: Int? = null,
    @ColumnInfo(name = "thr_mg") val thrMg: Int? = null,
    @ColumnInfo(name = "trp_mg") val trpMg: Int? = null,
    @ColumnInfo(name = "val_mg") val valMg: Int? = null,
    @ColumnInfo(name = "cys_mg") val cysMg: Int? = null,
    @ColumnInfo(name = "tyr_mg") val tyrMg: Int? = null,
    /** JSON list of ingredients used for FDC amino lookup (name, grams, optional match). */
    @ColumnInfo(name = "ingredients_json") val ingredientsJson: String? = null,
    /** FDC match counts; null when no lookup. Partial when matched < total. */
    @ColumnInfo(name = "amino_matched") val aminoMatched: Int? = null,
    @ColumnInfo(name = "amino_total") val aminoTotal: Int? = null,
) {
    /** True when some but not all ingredients matched for aminos. */
    val aminoPartial: Boolean get() =
        aminoMatched != null && aminoTotal != null && aminoMatched < aminoTotal && aminoMatched > 0

    val isRecipe: Boolean get() = kind == KIND_RECIPE
    val isSimple: Boolean get() = kind == KIND_SIMPLE || kind.isBlank()
    val isRestaurant: Boolean get() = kind == KIND_RESTAURANT
    /** Effective servings the batch yields (recipes); 1 for simple. */
    val yieldOrOne: Double get() = servingsYield?.takeIf { it > 0 } ?: 1.0

    companion object {
        const val KIND_SIMPLE = "simple"
        const val KIND_RECIPE = "recipe"
        const val KIND_RESTAURANT = "restaurant"
        const val SOURCE_PUBLISHED = "published"
        const val SOURCE_ESTIMATE = "estimate"
    }
}

/**
 * One ingredient line on a [MealEntity] recipe. Values are a snapshot for the amount used
 * (not per-100g), so past recipe totals stay stable if the source food later changes.
 */
@Entity(
    tableName = "recipe_ingredient",
    foreignKeys = [
        ForeignKey(MealEntity::class, ["id"], ["meal_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("meal_id")],
)
data class RecipeIngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "meal_id") val mealId: Long,
    val position: Int,
    val name: String,
    val amount: Double,
    /** g / ml / cup / tbsp / tsp / each — see [app.diurna.domain.IngredientUnit]. */
    val unit: String = "g",
    /** Resolved mass in grams for this line; null when volume had no density (blocked). */
    val grams: Double? = null,
    /** Density id from [app.diurna.domain.IngredientDensities], when volume was converted. */
    @ColumnInfo(name = "density_id") val densityId: String? = null,
    /** For unit=each: grams per one item used to resolve [grams]. */
    @ColumnInfo(name = "grams_per_each") val gramsPerEach: Double? = null,
    @ColumnInfo(name = "protein_g") val proteinG: Int = 0,
    @ColumnInfo(name = "carbs_g") val carbsG: Int = 0,
    @ColumnInfo(name = "fat_g") val fatG: Int = 0,
    val kcal: Int = 0,
    @ColumnInfo(name = "fiber_g") val fiberG: Int? = null,
    @ColumnInfo(name = "soluble_fiber_g") val solubleFiberG: Int? = null,
    @ColumnInfo(name = "insoluble_fiber_g") val insolubleFiberG: Int? = null,
    @ColumnInfo(name = "sugars_g") val sugarsG: Int? = null,
    @ColumnInfo(name = "added_sugars_g") val addedSugarsG: Int? = null,
    @ColumnInfo(name = "starch_g") val starchG: Int? = null,
    @ColumnInfo(name = "sat_fat_g") val satFatG: Int? = null,
    @ColumnInfo(name = "mono_fat_g") val monoFatG: Int? = null,
    @ColumnInfo(name = "poly_fat_g") val polyFatG: Int? = null,
    @ColumnInfo(name = "trans_fat_g") val transFatG: Int? = null,
    @ColumnInfo(name = "cholesterol_mg") val cholesterolMg: Int? = null,
    @ColumnInfo(name = "omega3_g") val omega3G: Int? = null,
    @ColumnInfo(name = "omega6_g") val omega6G: Int? = null,
    @ColumnInfo(name = "omega9_g") val omega9G: Int? = null,
    @ColumnInfo(name = "his_mg") val hisMg: Int? = null,
    @ColumnInfo(name = "ile_mg") val ileMg: Int? = null,
    @ColumnInfo(name = "leu_mg") val leuMg: Int? = null,
    @ColumnInfo(name = "lys_mg") val lysMg: Int? = null,
    @ColumnInfo(name = "met_mg") val metMg: Int? = null,
    @ColumnInfo(name = "phe_mg") val pheMg: Int? = null,
    @ColumnInfo(name = "thr_mg") val thrMg: Int? = null,
    @ColumnInfo(name = "trp_mg") val trpMg: Int? = null,
    @ColumnInfo(name = "val_mg") val valMg: Int? = null,
    @ColumnInfo(name = "cys_mg") val cysMg: Int? = null,
    @ColumnInfo(name = "tyr_mg") val tyrMg: Int? = null,
    /** Origin: manual / fdc / barcode / meal. */
    val source: String? = null,
    @ColumnInfo(name = "fdc_id") val fdcId: Int? = null,
    val barcode: String? = null,
    /** Library meal this line was copied from, if any. */
    @ColumnInfo(name = "source_meal_id") val sourceMealId: Long? = null,
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
    // Cardio pieces (exercise kind "cardio") use these instead of weight and reps; all optional.
    /** Metres. */
    @ColumnInfo(name = "distance_m") val distanceM: Int? = null,
    /** Seconds, tenths allowed. */
    @ColumnInfo(name = "duration_s") val durationS: Double? = null,
    /**
     * Average pace in seconds per 500 m, whatever the exercise shows it as (per km, mph…); as the
     * machine showed it when it did. Null means work it out from distance and time; some rowing
     * machines average per stroke and show a faster split than that.
     */
    @ColumnInfo(name = "split_s") val splitS: Double? = null,
    @ColumnInfo(name = "best_split_s") val bestSplitS: Double? = null,
    /** Average power, watts. */
    val watts: Int? = null,
    /** Energy as the machine reported it ("Cal"); not counted toward food targets. */
    val kcal: Int? = null,
    /** Average heart rate, bpm. */
    @ColumnInfo(name = "avg_hr") val avgHr: Int? = null,
) {
    /** Strength sets count once reps are in; cardio pieces once a distance or a time is. */
    val isDone: Boolean get() = reps != null || distanceM != null || durationS != null
}

@Entity(
    tableName = "food_entry",
    foreignKeys = [
        ForeignKey(DayEntity::class, ["date"], ["date"], onDelete = ForeignKey.CASCADE),
        // Deleting a saved meal keeps past entries and their numbers.
        ForeignKey(MealEntity::class, ["id"], ["meal_id"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("date"), Index("meal_id"), Index("barcode")],
)
data class FoodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val time: String,
    val text: String,
    /** Epoch millis of when it was entered; differs from [date] when backfilled. */
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
    // AI estimate, derived from [text] (and [photo]). Changing either clears these and re-estimates.
    @ColumnInfo(name = "kcal_low") val kcalLow: Int? = null,
    @ColumnInfo(name = "kcal_high") val kcalHigh: Int? = null,
    @ColumnInfo(name = "protein_g") val proteinG: Int? = null,
    @ColumnInfo(name = "carbs_g") val carbsG: Int? = null,
    @ColumnInfo(name = "fat_g") val fatG: Int? = null,
    @ColumnInfo(name = "fiber_g") val fiberG: Int? = null,
    @ColumnInfo(name = "soluble_fiber_g") val solubleFiberG: Int? = null,
    @ColumnInfo(name = "insoluble_fiber_g") val insolubleFiberG: Int? = null,
    @ColumnInfo(name = "sugars_g") val sugarsG: Int? = null,
    @ColumnInfo(name = "added_sugars_g") val addedSugarsG: Int? = null,
    @ColumnInfo(name = "starch_g") val starchG: Int? = null,
    @ColumnInfo(name = "sat_fat_g") val satFatG: Int? = null,
    @ColumnInfo(name = "mono_fat_g") val monoFatG: Int? = null,
    @ColumnInfo(name = "poly_fat_g") val polyFatG: Int? = null,
    @ColumnInfo(name = "trans_fat_g") val transFatG: Int? = null,
    @ColumnInfo(name = "cholesterol_mg") val cholesterolMg: Int? = null,
    @ColumnInfo(name = "omega3_g") val omega3G: Int? = null,
    @ColumnInfo(name = "omega6_g") val omega6G: Int? = null,
    @ColumnInfo(name = "omega9_g") val omega9G: Int? = null,
    @ColumnInfo(name = "his_mg") val hisMg: Int? = null,
    @ColumnInfo(name = "ile_mg") val ileMg: Int? = null,
    @ColumnInfo(name = "leu_mg") val leuMg: Int? = null,
    @ColumnInfo(name = "lys_mg") val lysMg: Int? = null,
    @ColumnInfo(name = "met_mg") val metMg: Int? = null,
    @ColumnInfo(name = "phe_mg") val pheMg: Int? = null,
    @ColumnInfo(name = "thr_mg") val thrMg: Int? = null,
    @ColumnInfo(name = "trp_mg") val trpMg: Int? = null,
    @ColumnInfo(name = "val_mg") val valMg: Int? = null,
    @ColumnInfo(name = "cys_mg") val cysMg: Int? = null,
    @ColumnInfo(name = "tyr_mg") val tyrMg: Int? = null,
    @ColumnInfo(name = "ingredients_json") val ingredientsJson: String? = null,
    @ColumnInfo(name = "amino_matched") val aminoMatched: Int? = null,
    @ColumnInfo(name = "amino_total") val aminoTotal: Int? = null,
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
    /**
     * Primary photo (first in the entry's [FoodPhotoEntity] gallery), a file name in [FoodPhotos].
     * Kept denormalized so AI estimate paths stay a single column; the gallery holds all photos.
     */
    val photo: String? = null,
    /** Package barcode when logged from a scan or a saved meal that has one. Kept even if OFF failed. */
    val barcode: String? = null,
    /**
     * Open Food Facts source snapshot ([OffSnapshot.encode]) at lookup time. Public product data;
     * raw OFF JSON inside is size-capped. Never silently overwrites the user's logged macros.
     */
    @ColumnInfo(name = "off_snapshot_json") val offSnapshotJson: String? = null,
) {
    val isMeal: Boolean get() = source == SOURCE_MEAL
    val aminoPartial: Boolean get() =
        aminoMatched != null && aminoTotal != null && aminoMatched < aminoTotal && aminoMatched > 0

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

data class SetRow(
    val date: String, val exerciseId: Long, val name: String, val loggedId: Long, val weight: Double?, val reps: Int?,
    val kind: String = ExerciseEntity.KIND_STRENGTH,
    val cardioStyle: String? = null,
    val distanceM: Int? = null, val durationS: Double? = null, val splitS: Double? = null, val bestSplitS: Double? = null,
    val watts: Int? = null, val kcal: Int? = null, val avgHr: Int? = null,
)

data class DateName(
    val date: String,
    val name: String,
    val exercises: Int = 0,
    /** The workout's cardio exercise, when it has one; see [app.diurna.domain.DayText.workoutTitle]. */
    val cardioName: String? = null,
    val cardioCount: Int = 0,
)

data class DateKcal(val date: String, val kcal: Int)

/** Per-day macro grams from estimated food entries (P/C/F nulls summed as 0; optional sub-macros stay null via SUM). */
data class DateMacros(
    val date: String,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int? = null,
    val solubleFiberG: Int? = null,
    val insolubleFiberG: Int? = null,
    val sugarsG: Int? = null,
    val addedSugarsG: Int? = null,
    val starchG: Int? = null,
    val satFatG: Int? = null,
    val monoFatG: Int? = null,
    val polyFatG: Int? = null,
    val transFatG: Int? = null,
    val cholesterolMg: Int? = null,
    val omega3G: Int? = null,
    val omega6G: Int? = null,
    val omega9G: Int? = null,
    val hisMg: Int? = null,
    val ileMg: Int? = null,
    val leuMg: Int? = null,
    val lysMg: Int? = null,
    val metMg: Int? = null,
    val pheMg: Int? = null,
    val thrMg: Int? = null,
    val trpMg: Int? = null,
    val valMg: Int? = null,
    val cysMg: Int? = null,
    val tyrMg: Int? = null,
    /** True when any entry that day had a partial FDC amino match. */
    val aminoPartial: Boolean = false,
)

data class CatalogExercise(
    val id: Long, val name: String, val hidden: Boolean, val logged: Int, val inRoutine: Int,
    val kind: String = ExerciseEntity.KIND_STRENGTH,
    val cardioStyle: String? = null,
) {
    val inUse: Boolean get() = logged > 0 || inRoutine > 0
    val isCardio: Boolean get() = kind == ExerciseEntity.KIND_CARDIO
    val style: Cardio.Style get() = Cardio.Style.of(cardioStyle)
}

/** Local cache of FDC Foundation/SR Legacy amino profiles keyed by normalized ingredient query. */

/**
 * One JPEG attached to a food entry or saved meal/recipe. Files live in [FoodPhotos]; this row
 * stores only the name. [food_entry.photo] remains the primary (position 0) for AI estimates.
 */
@Entity(
    tableName = "food_photo",
    indices = [
        Index(value = ["owner_type", "owner_id"]),
        Index("file_name"),
    ],
)
data class FoodPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [OWNER_ENTRY] or [OWNER_MEAL]. */
    @ColumnInfo(name = "owner_type") val ownerType: String,
    @ColumnInfo(name = "owner_id") val ownerId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    val position: Int = 0,
) {
    companion object {
        const val OWNER_ENTRY = "entry"
        const val OWNER_MEAL = "meal"
        /** Soft cap so galleries and AI payloads stay small. */
        const val MAX_PER_OWNER = 5
    }
}

@Entity(tableName = "fdc_food_cache")
data class FdcFoodCacheEntity(
    @PrimaryKey @ColumnInfo(name = "query_key") val queryKey: String,
    @ColumnInfo(name = "fdc_id") val fdcId: Int,
    val description: String,
    @ColumnInfo(name = "his_mg_per_100") val hisMgPer100: Int? = null,
    @ColumnInfo(name = "ile_mg_per_100") val ileMgPer100: Int? = null,
    @ColumnInfo(name = "leu_mg_per_100") val leuMgPer100: Int? = null,
    @ColumnInfo(name = "lys_mg_per_100") val lysMgPer100: Int? = null,
    @ColumnInfo(name = "met_mg_per_100") val metMgPer100: Int? = null,
    @ColumnInfo(name = "phe_mg_per_100") val pheMgPer100: Int? = null,
    @ColumnInfo(name = "thr_mg_per_100") val thrMgPer100: Int? = null,
    @ColumnInfo(name = "trp_mg_per_100") val trpMgPer100: Int? = null,
    @ColumnInfo(name = "val_mg_per_100") val valMgPer100: Int? = null,
    @ColumnInfo(name = "cys_mg_per_100") val cysMgPer100: Int? = null,
    @ColumnInfo(name = "tyr_mg_per_100") val tyrMgPer100: Int? = null,
    @ColumnInfo(name = "cached_at") val cachedAt: Long,
)
