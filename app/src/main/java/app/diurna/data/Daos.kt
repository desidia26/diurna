package app.diurna.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DayDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(day: DayEntity)

    @Query("SELECT * FROM day WHERE date = :date")
    suspend fun get(date: String): DayEntity?

    @Query("SELECT * FROM day WHERE date = :date")
    fun observe(date: String): Flow<DayEntity?>

    @Query("SELECT * FROM day WHERE date BETWEEN :from AND :to")
    fun observeRange(from: String, to: String): Flow<List<DayEntity>>

    @Query("UPDATE day SET alarm_override = :value WHERE date = :date")
    suspend fun setOverride(date: String, value: String?)

    @Query("UPDATE day SET scheduled_alarm = :value WHERE date = :date")
    suspend fun setScheduled(date: String, value: String?)

    @Query("UPDATE day SET scanned_at = :at, morning_source = :source WHERE date = :date")
    suspend fun setMorning(date: String, at: String?, source: String?)

    /** Adds to a day's step total, starting it at 0 if it had none. */
    @Query("UPDATE day SET steps = COALESCE(steps, 0) + :add WHERE date = :date")
    suspend fun addSteps(date: String, add: Long)

    @Query("UPDATE day SET mood = :value WHERE date = :date")
    suspend fun setMood(date: String, value: Int?)

    @Query("UPDATE day SET energy = :value WHERE date = :date")
    suspend fun setEnergy(date: String, value: Int?)

    @Query("UPDATE day SET body_weight = :lb WHERE date = :date")
    suspend fun setBodyWeight(date: String, lb: Double?)

    /** Latest weigh-in on or before [date], so targets follow you as weight changes. */
    @Query("SELECT body_weight FROM day WHERE date <= :date AND body_weight IS NOT NULL ORDER BY date DESC LIMIT 1")
    suspend fun latestWeight(date: String): Double?

    @Query("SELECT body_weight FROM day WHERE date <= :date AND body_weight IS NOT NULL ORDER BY date DESC LIMIT 1")
    fun observeLatestWeight(date: String): Flow<Double?>

    @Query("SELECT MIN(date) FROM day")
    suspend fun firstDate(): String?

    @Query("SELECT MIN(date) FROM day")
    fun observeFirstDate(): Flow<String?>

    @Query("SELECT MAX(date) FROM day WHERE finalized = 1")
    suspend fun lastFinalizedDate(): String?

    @Query("SELECT date FROM day WHERE finalized = 0 AND date <= :through ORDER BY date")
    suspend fun unfinalizedThrough(through: String): List<String>

    @Query(
        """UPDATE day SET finalized = 1, scheduled_alarm = :alarm, planned_workout = :planned,
           target_kcal = :kcal, target_protein_g = :protein, target_carbs_g = :carbs, target_fat_g = :fat,
           steps = :steps
           WHERE date = :date"""
    )
    suspend fun finalize(date: String, alarm: String, planned: String?, kcal: Int?, protein: Int?, carbs: Int?, fat: Int?, steps: Long?)
}

@Dao
interface ExerciseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(exercise: ExerciseEntity): Long

    @Query("SELECT id FROM exercise WHERE name = :name COLLATE NOCASE ORDER BY id LIMIT 1")
    suspend fun idFor(name: String): Long?

    /**
     * Visible exercise names for suggestions, the ones you've logged most often first. Cardio is
     * left out unless [cardio] (Setup's Cardio switch), so it never crowds out lifts for people who don't do it.
     */
    @Query(
        """SELECT e.name FROM exercise e LEFT JOIN logged_exercise le ON le.exercise_id = e.id
           WHERE e.hidden = 0 AND (:cardio OR e.kind != 'cardio')
           GROUP BY e.id ORDER BY COUNT(le.id) DESC, e.name COLLATE NOCASE"""
    )
    fun observeNamesByUse(cardio: Boolean): Flow<List<String>>

    /** The whole catalog for the Library page, with how often each is used. */
    @Query(
        """SELECT e.id, e.name, e.hidden, e.kind, e.cardio_style AS cardioStyle,
             (SELECT COUNT(*) FROM logged_exercise le WHERE le.exercise_id = e.id) AS logged,
             (SELECT COUNT(*) FROM template_exercise te WHERE te.exercise_id = e.id) AS inRoutine
           FROM exercise e ORDER BY e.hidden, e.name COLLATE NOCASE"""
    )
    fun observeCatalog(): Flow<List<CatalogExercise>>

    @Query("SELECT COUNT(*) FROM exercise WHERE name = :name COLLATE NOCASE AND id != :exceptId")
    suspend fun nameTakenByOther(name: String, exceptId: Long): Int

    @Query("UPDATE exercise SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE exercise SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun get(id: Long): ExerciseEntity?

    /** Only while nothing is logged, so no history mixes sets and pieces; returns rows changed. */
    @Query(
        """UPDATE exercise SET kind = :kind, cardio_style = :style WHERE id = :id
           AND NOT EXISTS (SELECT 1 FROM logged_exercise WHERE exercise_id = :id)"""
    )
    suspend fun setKindIfUnlogged(id: Long, kind: String, style: String?): Int

    /** Only for cardio; a style just changes how pieces are typed and shown. */
    @Query("UPDATE exercise SET cardio_style = :style WHERE id = :id AND kind = 'cardio'")
    suspend fun setCardioStyle(id: Long, style: String): Int

    /** Only removes exercises no workout or routine refers to; returns rows deleted. */
    @Query(
        """DELETE FROM exercise WHERE id = :id
           AND NOT EXISTS (SELECT 1 FROM logged_exercise WHERE exercise_id = :id)
           AND NOT EXISTS (SELECT 1 FROM template_exercise WHERE exercise_id = :id)"""
    )
    suspend fun deleteIfUnused(id: Long): Int
}

@Dao
interface MealDao {
    /** Most-logged first, so the meals you actually eat lead the suggestions. */
    @Query(
        """SELECT m.* FROM meal m LEFT JOIN food_entry f ON f.meal_id = m.id
           GROUP BY m.id ORDER BY COUNT(f.id) DESC, m.name COLLATE NOCASE"""
    )
    fun observeByUse(): Flow<List<MealEntity>>

    @Query("SELECT * FROM meal WHERE id = :id")
    suspend fun get(id: Long): MealEntity?

    @Query("SELECT COUNT(*) FROM meal WHERE name = :name COLLATE NOCASE AND id != :exceptId")
    suspend fun nameTakenByOther(name: String, exceptId: Long): Int

    @Query("SELECT COUNT(*) FROM meal WHERE barcode = :code AND id != :exceptId")
    suspend fun barcodeTakenByOther(code: String, exceptId: Long): Int

    @Query("SELECT * FROM meal WHERE barcode = :code")
    suspend fun byBarcode(code: String): MealEntity?

    @Insert
    suspend fun insert(meal: MealEntity): Long

    @Update
    suspend fun update(meal: MealEntity)

    @Query("DELETE FROM meal WHERE id = :id")
    suspend fun delete(id: Long)

    /** Filter library by kind: null = all; "simple" / "recipe" / "restaurant". */
    @Query(
        """SELECT m.* FROM meal m LEFT JOIN food_entry f ON f.meal_id = m.id
           WHERE (:kind IS NULL OR m.kind = :kind)
           GROUP BY m.id ORDER BY COUNT(f.id) DESC, m.name COLLATE NOCASE"""
    )
    fun observeByUseFiltered(kind: String?): Flow<List<MealEntity>>
}

@Dao
interface RecipeIngredientDao {
    @Query("SELECT * FROM recipe_ingredient WHERE meal_id = :mealId ORDER BY position, id")
    fun observeForMeal(mealId: Long): Flow<List<RecipeIngredientEntity>>

    @Query("SELECT * FROM recipe_ingredient WHERE meal_id = :mealId ORDER BY position, id")
    suspend fun forMeal(mealId: Long): List<RecipeIngredientEntity>

    @Insert
    suspend fun insertAll(rows: List<RecipeIngredientEntity>)

    @Query("DELETE FROM recipe_ingredient WHERE meal_id = :mealId")
    suspend fun deleteForMeal(mealId: Long)
}

/**
 * Case-insensitive, so "bench press" and "Bench press" share history. Typing a hidden exercise's
 * name brings it back. Call inside a transaction.
 */
suspend fun ExerciseDao.getOrCreate(name: String): Long =
    idFor(name)?.also { setHidden(it, false) } ?: insertIgnore(ExerciseEntity(name = name))

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routine_day ORDER BY weekday")
    fun observeDays(): Flow<List<RoutineDayEntity>>

    @Query("SELECT * FROM routine_day WHERE weekday = :weekday")
    suspend fun getDay(weekday: Int): RoutineDayEntity?

    @Query("UPDATE routine_day SET alarm_time = :time WHERE weekday = :weekday")
    suspend fun setAlarm(weekday: Int, time: String)

    @Query("UPDATE routine_day SET template_id = :templateId WHERE weekday = :weekday")
    suspend fun setTemplate(weekday: Int, templateId: Long?)

    @Query("SELECT * FROM workout_template ORDER BY name")
    fun observeTemplates(): Flow<List<WorkoutTemplateEntity>>

    @Query("SELECT * FROM workout_template WHERE id = :id")
    suspend fun getTemplate(id: Long): WorkoutTemplateEntity?

    @Query(
        """SELECT te.template_id AS templateId, te.position AS position, e.name AS name
           FROM template_exercise te JOIN exercise e ON e.id = te.exercise_id
           ORDER BY te.template_id, te.position"""
    )
    fun observeTemplateExercises(): Flow<List<TemplateExerciseName>>

    @Query("SELECT exercise_id FROM template_exercise WHERE template_id = :templateId ORDER BY position")
    suspend fun templateExerciseIds(templateId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTemplate(template: WorkoutTemplateEntity): Long

    @Query("DELETE FROM workout_template WHERE id = :id")
    suspend fun deleteTemplate(id: Long)

    @Query("DELETE FROM template_exercise WHERE template_id = :templateId")
    suspend fun clearTemplateExercises(templateId: Long)

    @Insert
    suspend fun insertTemplateExercises(rows: List<TemplateExerciseEntity>)
}

/** A set counts as done: reps for strength, a distance or a time for a cardio piece. Alias the set table "s". */
private const val DONE = "(s.reps IS NOT NULL OR s.distance_m IS NOT NULL OR s.duration_s IS NOT NULL)"

@Dao
interface WorkoutDao {
    @Transaction
    @Query("SELECT * FROM day_workout WHERE date = :date")
    fun observe(date: String): Flow<WorkoutWithExercises?>

    @Query(
        """SELECT w.date, w.name,
             (SELECT COUNT(*) FROM logged_exercise le WHERE le.workout_id = w.id) AS exercises,
             (SELECT e.name FROM logged_exercise le JOIN exercise e ON e.id = le.exercise_id
                WHERE le.workout_id = w.id AND e.kind = 'cardio' ORDER BY le.position LIMIT 1) AS cardioName,
             (SELECT COUNT(*) FROM logged_exercise le JOIN exercise e ON e.id = le.exercise_id
                WHERE le.workout_id = w.id AND e.kind = 'cardio') AS cardioCount
           FROM day_workout w WHERE w.date BETWEEN :from AND :to"""
    )
    fun observeNames(from: String, to: String): Flow<List<DateName>>

    @Query("SELECT id FROM day_workout WHERE date = :date")
    suspend fun idFor(date: String): Long?

    @Insert
    suspend fun insertWorkout(workout: DayWorkoutEntity): Long

    @Query("DELETE FROM day_workout WHERE date = :date")
    suspend fun deleteWorkout(date: String)

    @Insert
    suspend fun insertLogged(logged: LoggedExerciseEntity): Long

    @Query("SELECT COALESCE(MAX(position), -1) FROM logged_exercise WHERE workout_id = :workoutId")
    suspend fun maxLoggedPosition(workoutId: Long): Int

    @Query("DELETE FROM logged_exercise WHERE id = :id")
    suspend fun deleteLogged(id: Long)

    @Query("SELECT * FROM logged_exercise WHERE id = :id")
    suspend fun getLogged(id: Long): LoggedExerciseEntity?

    @Query("SELECT * FROM logged_exercise WHERE workout_id = :workoutId ORDER BY position")
    suspend fun loggedIn(workoutId: Long): List<LoggedExerciseEntity>

    @Query("UPDATE logged_exercise SET position = :position WHERE id = :id")
    suspend fun setLoggedPosition(id: Long, position: Int)

    @Insert
    suspend fun insertSets(sets: List<ExerciseSetEntity>)

    @Query("SELECT * FROM exercise_set WHERE logged_exercise_id = :loggedId ORDER BY position")
    suspend fun sets(loggedId: Long): List<ExerciseSetEntity>

    @Query("UPDATE exercise_set SET weight = :weight, reps = :reps WHERE id = :id")
    suspend fun updateSet(id: Long, weight: Double?, reps: Int?)

    @Query(
        """UPDATE exercise_set SET distance_m = :distanceM, duration_s = :durationS, split_s = :splitS,
             best_split_s = :bestSplitS, watts = :watts, kcal = :kcal, avg_hr = :avgHr WHERE id = :id"""
    )
    suspend fun updatePiece(
        id: Long, distanceM: Int?, durationS: Double?, splitS: Double?, bestSplitS: Double?,
        watts: Int?, kcal: Int?, avgHr: Int?,
    )

    @Query("DELETE FROM exercise_set WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("DELETE FROM exercise_set WHERE logged_exercise_id = :loggedId")
    suspend fun clearSets(loggedId: Long)

    /**
     * The most recent earlier session of an exercise that has at least one set filled in: reps
     * for strength, a distance or time for cardio (the same rule as [ExerciseSetEntity.isDone]).
     */
    @Query(
        """SELECT le.id AS loggedExerciseId, w.date AS date
           FROM logged_exercise le JOIN day_workout w ON w.id = le.workout_id
           WHERE le.exercise_id = :exerciseId AND w.date < :before
             AND EXISTS (SELECT 1 FROM exercise_set s WHERE s.logged_exercise_id = le.id AND $DONE)
           ORDER BY w.date DESC LIMIT 1"""
    )
    suspend fun lastTimeRef(exerciseId: Long, before: String): LastTimeRef?

    @Query("SELECT * FROM exercise_set s WHERE logged_exercise_id = :loggedId AND $DONE ORDER BY position")
    suspend fun filledSets(loggedId: Long): List<ExerciseSetEntity>

    /** Every set ever logged, oldest first. All of history, because PRs are judged against it. */
    @Query(
        """SELECT w.date AS date, le.exercise_id AS exerciseId, e.name AS name, le.id AS loggedId, s.weight AS weight, s.reps AS reps,
             e.kind AS kind, e.cardio_style AS cardioStyle, s.distance_m AS distanceM, s.duration_s AS durationS, s.split_s AS splitS,
             s.best_split_s AS bestSplitS, s.watts AS watts, s.kcal AS kcal, s.avg_hr AS avgHr
           FROM exercise_set s
           JOIN logged_exercise le ON le.id = s.logged_exercise_id
           JOIN day_workout w ON w.id = le.workout_id
           JOIN exercise e ON e.id = le.exercise_id
           ORDER BY w.date, le.position, s.position"""
    )
    fun observeSetRows(): Flow<List<SetRow>>
}

@Dao
interface FoodDao {
    @Query("SELECT * FROM food_entry WHERE date = :date ORDER BY time, id")
    fun observe(date: String): Flow<List<FoodEntryEntity>>

    /** Each day's calories from estimated entries, the same sum as the Day screen's (midpoint per entry). */
    @Query(
        "SELECT date, SUM((kcal_low + kcal_high) / 2) AS kcal FROM food_entry " +
            "WHERE date BETWEEN :from AND :to AND estimated_at IS NOT NULL GROUP BY date"
    )
    fun observeKcal(from: String, to: String): Flow<List<DateKcal>>

    /** Each day's protein/carbs/fat grams from estimated entries. Missing P/C/F count as 0; optional sub-macros stay null if all null. */
    @Query(
        """SELECT date,
              SUM(COALESCE(protein_g, 0)) AS proteinG,
              SUM(COALESCE(carbs_g, 0)) AS carbsG,
              SUM(COALESCE(fat_g, 0)) AS fatG,
              SUM(fiber_g) AS fiberG,
              SUM(soluble_fiber_g) AS solubleFiberG,
              SUM(insoluble_fiber_g) AS insolubleFiberG,
              SUM(sugars_g) AS sugarsG,
              SUM(added_sugars_g) AS addedSugarsG,
              SUM(starch_g) AS starchG,
              SUM(sat_fat_g) AS satFatG,
              SUM(mono_fat_g) AS monoFatG,
              SUM(poly_fat_g) AS polyFatG,
              SUM(trans_fat_g) AS transFatG,
              SUM(cholesterol_mg) AS cholesterolMg,
              SUM(omega3_g) AS omega3G,
              SUM(omega6_g) AS omega6G,
              SUM(omega9_g) AS omega9G,
              SUM(his_mg) AS hisMg,
              SUM(ile_mg) AS ileMg,
              SUM(leu_mg) AS leuMg,
              SUM(lys_mg) AS lysMg,
              SUM(met_mg) AS metMg,
              SUM(phe_mg) AS pheMg,
              SUM(thr_mg) AS thrMg,
              SUM(trp_mg) AS trpMg,
              SUM(val_mg) AS valMg,
              SUM(cys_mg) AS cysMg,
              SUM(tyr_mg) AS tyrMg,
              MAX(CASE WHEN amino_matched IS NOT NULL AND amino_total IS NOT NULL
                        AND amino_matched < amino_total AND amino_matched > 0
                   THEN 1 ELSE 0 END) AS aminoPartial
           FROM food_entry
           WHERE date BETWEEN :from AND :to AND estimated_at IS NOT NULL
           GROUP BY date"""
    )
    fun observeMacros(from: String, to: String): Flow<List<DateMacros>>

    /** How many food entries have ever been estimated; Metrics hides the macros card while 0. */
    @Query("SELECT COUNT(*) FROM food_entry WHERE estimated_at IS NOT NULL")
    fun observeEstimatedFoodCount(): Flow<Int>

    @Insert
    suspend fun insert(entry: FoodEntryEntity): Long

    @Query("DELETE FROM food_entry WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM food_entry WHERE id = :id")
    suspend fun get(id: Long): FoodEntryEntity?

    /** Waiting for an estimate: never estimated and no recorded failure. */
    @Query("SELECT * FROM food_entry WHERE estimated_at IS NULL AND estimate_error IS NULL ORDER BY logged_at")
    suspend fun pendingEstimates(): List<FoodEntryEntity>

    @Query(
        """UPDATE food_entry SET kcal_low = :kcalLow, kcal_high = :kcalHigh, protein_g = :protein, carbs_g = :carbs,
           fat_g = :fat,
           fiber_g = :fiber, soluble_fiber_g = :solubleFiber, insoluble_fiber_g = :insolubleFiber,
           sugars_g = :sugars, added_sugars_g = :addedSugars, starch_g = :starch,
           sat_fat_g = :satFat, mono_fat_g = :monoFat, poly_fat_g = :polyFat, trans_fat_g = :transFat,
           cholesterol_mg = :cholesterol, omega3_g = :omega3, omega6_g = :omega6, omega9_g = :omega9,
           his_mg = :his, ile_mg = :ile, leu_mg = :leu, lys_mg = :lys, met_mg = :met,
           phe_mg = :phe, thr_mg = :thr, trp_mg = :trp, val_mg = :valMg, cys_mg = :cys, tyr_mg = :tyr,
           ingredients_json = :ingredientsJson, amino_matched = :aminoMatched, amino_total = :aminoTotal,
           estimate_note = :note, estimate_model = :model, estimated_at = :at, estimate_error = NULL
           WHERE id = :id AND text = :forText AND photo IS :forPhoto"""
    )
    suspend fun setEstimate(
        id: Long, forText: String, forPhoto: String?, kcalLow: Int, kcalHigh: Int, protein: Int, carbs: Int, fat: Int,
        fiber: Int?, solubleFiber: Int?, insolubleFiber: Int?,
        sugars: Int?, addedSugars: Int?, starch: Int?,
        satFat: Int?, monoFat: Int?, polyFat: Int?, transFat: Int?,
        cholesterol: Int?, omega3: Int?, omega6: Int?, omega9: Int?,
        his: Int?, ile: Int?, leu: Int?, lys: Int?, met: Int?,
        phe: Int?, thr: Int?, trp: Int?, valMg: Int?, cys: Int?, tyr: Int?,
        ingredientsJson: String?, aminoMatched: Int?, aminoTotal: Int?,
        note: String, model: String, at: Long,
    )

    @Query("UPDATE food_entry SET estimate_error = :error WHERE id = :id")
    suspend fun setEstimateError(id: Long, error: String)

    @Query("UPDATE food_entry SET estimate_error = :error WHERE estimated_at IS NULL AND estimate_error IS NULL")
    suspend fun failAllPending(error: String)

    /** Edits clear the estimate; the text is the source of truth and gets re-estimated. */
    /** New text or photo means new food: the entry becomes an AI entry and is re-estimated. */
    @Query(
        """UPDATE food_entry SET text = :text, time = :time, photo = :photo, kcal_low = NULL, kcal_high = NULL, protein_g = NULL,
           carbs_g = NULL, fat_g = NULL, fiber_g = NULL, soluble_fiber_g = NULL, insoluble_fiber_g = NULL,
           sugars_g = NULL, added_sugars_g = NULL, starch_g = NULL,
           sat_fat_g = NULL, mono_fat_g = NULL, poly_fat_g = NULL, trans_fat_g = NULL,
           cholesterol_mg = NULL, omega3_g = NULL, omega6_g = NULL, omega9_g = NULL,
           his_mg = NULL, ile_mg = NULL, leu_mg = NULL, lys_mg = NULL, met_mg = NULL,
           phe_mg = NULL, thr_mg = NULL, trp_mg = NULL, val_mg = NULL, cys_mg = NULL, tyr_mg = NULL,
           ingredients_json = NULL, amino_matched = NULL, amino_total = NULL,
           estimate_note = NULL, estimate_model = NULL, estimated_at = NULL,
           estimate_error = NULL, source = 'ai', meal_id = NULL, servings = 1.0 WHERE id = :id"""
    )
    suspend fun edit(id: Long, text: String, time: String, photo: String?)

    /** A photo added to or taken off a saved-meal entry: its numbers are exact, so nothing is re-estimated. */
    @Query("UPDATE food_entry SET photo = :photo WHERE id = :id")
    suspend fun setPhoto(id: Long, photo: String?)

    /** Persists barcode + OFF snapshot without touching macros or estimate identity. */
    @Query("UPDATE food_entry SET barcode = :barcode, off_snapshot_json = :offSnapshotJson WHERE id = :id")
    suspend fun setBarcodeSnapshot(id: Long, barcode: String?, offSnapshotJson: String?)

    @Query("SELECT photo FROM food_entry WHERE photo IS NOT NULL")
    suspend fun photosInUse(): List<String>

    /** Sets a meal entry's numbers (already multiplied by servings). */
    @Query(
        """UPDATE food_entry SET source = 'meal', meal_id = :mealId, servings = :servings, text = :text,
           kcal_low = :kcal, kcal_high = :kcal, protein_g = :protein, carbs_g = :carbs, fat_g = :fat,
           fiber_g = :fiber, soluble_fiber_g = :solubleFiber, insoluble_fiber_g = :insolubleFiber,
           sugars_g = :sugars, added_sugars_g = :addedSugars, starch_g = :starch,
           sat_fat_g = :satFat, mono_fat_g = :monoFat, poly_fat_g = :polyFat, trans_fat_g = :transFat,
           cholesterol_mg = :cholesterol, omega3_g = :omega3, omega6_g = :omega6, omega9_g = :omega9,
           his_mg = :his, ile_mg = :ile, leu_mg = :leu, lys_mg = :lys, met_mg = :met,
           phe_mg = :phe, thr_mg = :thr, trp_mg = :trp, val_mg = :valMg, cys_mg = :cys, tyr_mg = :tyr,
           ingredients_json = :ingredientsJson, amino_matched = :aminoMatched, amino_total = :aminoTotal,
           estimate_note = NULL, estimate_model = NULL, estimated_at = :at, estimate_error = NULL WHERE id = :id"""
    )
    suspend fun setMeal(
        id: Long, mealId: Long?, servings: Double, text: String, kcal: Int, protein: Int, carbs: Int, fat: Int,
        fiber: Int?, solubleFiber: Int?, insolubleFiber: Int?,
        sugars: Int?, addedSugars: Int?, starch: Int?,
        satFat: Int?, monoFat: Int?, polyFat: Int?, transFat: Int?,
        cholesterol: Int?, omega3: Int?, omega6: Int?, omega9: Int?,
        his: Int?, ile: Int?, leu: Int?, lys: Int?, met: Int?,
        phe: Int?, thr: Int?, trp: Int?, valMg: Int?, cys: Int?, tyr: Int?,
        ingredientsJson: String?, aminoMatched: Int?, aminoTotal: Int?,
        at: Long,
    )

    @Query("UPDATE food_entry SET time = :time WHERE id = :id")
    suspend fun setTime(id: Long, time: String)

    @Query("UPDATE food_entry SET estimate_error = NULL WHERE estimate_error IS NOT NULL")
    suspend fun clearEstimateErrors()

    /**
     * Past estimated entries in an optional inclusive date range (ISO keys).
     * Pending / never-estimated rows are excluded — the normal estimator owns those.
     */
    @Query(
        """SELECT * FROM food_entry
           WHERE estimated_at IS NOT NULL
             AND (:from IS NULL OR date >= :from)
             AND (:to IS NULL OR date <= :to)
           ORDER BY date, id"""
    )
    suspend fun estimatedInRange(from: String?, to: String?): List<FoodEntryEntity>

    /**
     * Writes detail fields only. Guards on stored macros + estimate identity so a concurrent
     * edit / re-estimate makes this a no-op — kcal/P/C/F/note/model/estimatedAt never change here.
     */
    @Query(
        """UPDATE food_entry SET
           fiber_g = :fiber, soluble_fiber_g = :solubleFiber, insoluble_fiber_g = :insolubleFiber,
           sugars_g = :sugars, added_sugars_g = :addedSugars, starch_g = :starch,
           sat_fat_g = :satFat, mono_fat_g = :monoFat, poly_fat_g = :polyFat, trans_fat_g = :transFat,
           cholesterol_mg = :cholesterol, omega3_g = :omega3, omega6_g = :omega6, omega9_g = :omega9,
           his_mg = :his, ile_mg = :ile, leu_mg = :leu, lys_mg = :lys, met_mg = :met,
           phe_mg = :phe, thr_mg = :thr, trp_mg = :trp, val_mg = :valMg, cys_mg = :cys, tyr_mg = :tyr,
           ingredients_json = :ingredientsJson, amino_matched = :aminoMatched, amino_total = :aminoTotal
           WHERE id = :id
             AND kcal_low IS :kcalLow AND kcal_high IS :kcalHigh
             AND protein_g IS :protein AND carbs_g IS :carbs AND fat_g IS :fat
             AND estimate_note IS :note AND estimate_model IS :model AND estimated_at IS :estimatedAt"""
    )
    suspend fun fillDetailNulls(
        id: Long,
        fiber: Int?, solubleFiber: Int?, insolubleFiber: Int?,
        sugars: Int?, addedSugars: Int?, starch: Int?,
        satFat: Int?, monoFat: Int?, polyFat: Int?, transFat: Int?,
        cholesterol: Int?, omega3: Int?, omega6: Int?, omega9: Int?,
        his: Int?, ile: Int?, leu: Int?, lys: Int?, met: Int?,
        phe: Int?, thr: Int?, trp: Int?, valMg: Int?, cys: Int?, tyr: Int?,
        ingredientsJson: String?, aminoMatched: Int?, aminoTotal: Int?,
        kcalLow: Int?, kcalHigh: Int?, protein: Int?, carbs: Int?, fat: Int?,
        note: String?, model: String?, estimatedAt: Long?,
    )
}

@Dao
interface FdcFoodCacheDao {
    @Query("SELECT * FROM fdc_food_cache WHERE query_key = :key")
    suspend fun get(key: String): FdcFoodCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FdcFoodCacheEntity)
}
