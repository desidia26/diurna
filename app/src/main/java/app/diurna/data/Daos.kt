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

    /** Visible exercise names for suggestions, the ones you've logged most often first. */
    @Query(
        """SELECT e.name FROM exercise e LEFT JOIN logged_exercise le ON le.exercise_id = e.id
           WHERE e.hidden = 0
           GROUP BY e.id ORDER BY COUNT(le.id) DESC, e.name COLLATE NOCASE"""
    )
    fun observeNamesByUse(): Flow<List<String>>

    /** The whole catalog for the Library page, with how often each is used. */
    @Query(
        """SELECT e.id, e.name, e.hidden,
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

@Dao
interface WorkoutDao {
    @Transaction
    @Query("SELECT * FROM day_workout WHERE date = :date")
    fun observe(date: String): Flow<WorkoutWithExercises?>

    @Query("SELECT date, name FROM day_workout WHERE date BETWEEN :from AND :to")
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

    @Query("DELETE FROM exercise_set WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("DELETE FROM exercise_set WHERE logged_exercise_id = :loggedId")
    suspend fun clearSets(loggedId: Long)

    /** The most recent earlier session of an exercise that has at least one set with reps filled in. */
    @Query(
        """SELECT le.id AS loggedExerciseId, w.date AS date
           FROM logged_exercise le JOIN day_workout w ON w.id = le.workout_id
           WHERE le.exercise_id = :exerciseId AND w.date < :before
             AND EXISTS (SELECT 1 FROM exercise_set s WHERE s.logged_exercise_id = le.id AND s.reps IS NOT NULL)
           ORDER BY w.date DESC LIMIT 1"""
    )
    suspend fun lastTimeRef(exerciseId: Long, before: String): LastTimeRef?

    @Query("SELECT * FROM exercise_set WHERE logged_exercise_id = :loggedId AND reps IS NOT NULL ORDER BY position")
    suspend fun filledSets(loggedId: Long): List<ExerciseSetEntity>

    /** Every set ever logged, oldest first. All of history, because PRs are judged against it. */
    @Query(
        """SELECT w.date AS date, le.exercise_id AS exerciseId, e.name AS name, le.id AS loggedId, s.weight AS weight, s.reps AS reps
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
           fat_g = :fat, estimate_note = :note, estimate_model = :model, estimated_at = :at, estimate_error = NULL
           WHERE id = :id AND text = :forText AND photo IS :forPhoto"""
    )
    suspend fun setEstimate(id: Long, forText: String, forPhoto: String?, kcalLow: Int, kcalHigh: Int, protein: Int, carbs: Int, fat: Int, note: String, model: String, at: Long)

    @Query("UPDATE food_entry SET estimate_error = :error WHERE id = :id")
    suspend fun setEstimateError(id: Long, error: String)

    @Query("UPDATE food_entry SET estimate_error = :error WHERE estimated_at IS NULL AND estimate_error IS NULL")
    suspend fun failAllPending(error: String)

    /** Edits clear the estimate; the text is the source of truth and gets re-estimated. */
    /** New text or photo means new food: the entry becomes an AI entry and is re-estimated. */
    @Query(
        """UPDATE food_entry SET text = :text, time = :time, photo = :photo, kcal_low = NULL, kcal_high = NULL, protein_g = NULL,
           carbs_g = NULL, fat_g = NULL, estimate_note = NULL, estimate_model = NULL, estimated_at = NULL,
           estimate_error = NULL, source = 'ai', meal_id = NULL, servings = 1.0 WHERE id = :id"""
    )
    suspend fun edit(id: Long, text: String, time: String, photo: String?)

    /** A photo added to or taken off a saved-meal entry: its numbers are exact, so nothing is re-estimated. */
    @Query("UPDATE food_entry SET photo = :photo WHERE id = :id")
    suspend fun setPhoto(id: Long, photo: String?)

    @Query("SELECT photo FROM food_entry WHERE photo IS NOT NULL")
    suspend fun photosInUse(): List<String>

    /** Sets a meal entry's numbers (already multiplied by servings). */
    @Query(
        """UPDATE food_entry SET source = 'meal', meal_id = :mealId, servings = :servings, text = :text,
           kcal_low = :kcal, kcal_high = :kcal, protein_g = :protein, carbs_g = :carbs, fat_g = :fat,
           estimate_note = NULL, estimate_model = NULL, estimated_at = :at, estimate_error = NULL WHERE id = :id"""
    )
    suspend fun setMeal(id: Long, mealId: Long?, servings: Double, text: String, kcal: Int, protein: Int, carbs: Int, fat: Int, at: Long)

    @Query("UPDATE food_entry SET time = :time WHERE id = :id")
    suspend fun setTime(id: Long, time: String)

    @Query("UPDATE food_entry SET estimate_error = NULL WHERE estimate_error IS NOT NULL")
    suspend fun clearEstimateErrors()
}
