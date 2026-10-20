package app.daybook.data

import androidx.room.withTransaction
import app.daybook.domain.AlarmRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
fun LocalDateTime.hhmm(): String = format(HM)
fun LocalDate.key(): String = toString()

data class LastTime(val date: LocalDate, val sets: List<ExerciseSetEntity>)

data class WeekRow(
    val date: LocalDate,
    val day: DayEntity?,
    val workoutName: String?,
    /** Calories from estimated food; null when nothing is estimated yet. */
    val kcal: Int?,
)

class DayRepository(
    private val db: AppDatabase,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val days = db.dayDao()
    private val routine = db.routineDao()
    private val workouts = db.workoutDao()
    private val exercises = db.exerciseDao()
    private val food = db.foodDao()

    /** Called after a day's mood or energy changes. */
    var onRatingChanged: suspend (LocalDate) -> Unit = {}

    /** Called after anything that can change which alarm should be armed. */
    var onAlarmInputsChanged: suspend () -> Unit = {}

    fun observeDay(date: LocalDate): Flow<DayEntity?> = days.observe(date.key())
    fun observeWorkout(date: LocalDate): Flow<WorkoutWithExercises?> = workouts.observe(date.key())

    fun observeWeek(start: LocalDate): Flow<List<WeekRow>> {
        val from = start.key()
        val to = start.plusDays(6).key()
        return combine(
            days.observeRange(from, to),
            workouts.observeNames(from, to),
            food.observeKcal(from, to),
        ) { dayRows, names, kcal ->
            (0L..6L).map { i ->
                val d = start.plusDays(i)
                WeekRow(
                    date = d,
                    day = dayRows.find { it.date == d.key() },
                    workoutName = names.find { it.date == d.key() }?.name,
                    kcal = kcal.find { it.date == d.key() }?.kcal,
                )
            }
        }
    }

    private suspend fun ensureDay(date: LocalDate) = days.insertIgnore(DayEntity(date.key()))

    // ---- Morning / alarm ----

    private suspend fun routineAlarm(date: LocalDate): String =
        routine.getDay(date.dayOfWeek.value)?.alarmTime ?: AppDatabase.DEFAULT_ALARM

    /** The alarm that applies to [date] right now: a time or [AlarmRules.OFF]. */
    suspend fun effectiveAlarm(date: LocalDate): String {
        val day = days.get(date.key())
        return AlarmRules.effective(date, now(), day?.alarmOverride, routineAlarm(date), day?.scheduledAlarm)
    }

    suspend fun alarmInputs(date: LocalDate): AlarmRules.DayAlarmInputs =
        AlarmRules.DayAlarmInputs(effectiveAlarm(date), scanned = days.get(date.key())?.scannedAt != null)

    /**
     * Pins the alarm of any already-locked day (today, and tomorrow after 10pm) that has no
     * frozen value yet. Run this before routine edits so they can't move a locked alarm.
     */
    suspend fun freezeLockedDays() {
        val t = now()
        for (date in listOf(t.toLocalDate(), t.toLocalDate().plusDays(1))) {
            if (!AlarmRules.isLocked(date, t)) continue
            val day = days.get(date.key())
            if (day?.scheduledAlarm != null) continue
            ensureDay(date)
            days.setScheduled(date.key(), AlarmRules.resolve(day?.alarmOverride, routineAlarm(date)))
        }
    }

    /** Records the alarm being armed. Locked days keep their first frozen value. */
    suspend fun markScheduled(date: LocalDate, time: String) {
        val day = days.get(date.key())
        if (AlarmRules.isLocked(date, now()) && day?.scheduledAlarm != null) return
        ensureDay(date)
        days.setScheduled(date.key(), time)
    }

    /** Sets a time, [AlarmRules.OFF], or null (follow routine). Returns false once the day is locked. */
    suspend fun setAlarmOverride(date: LocalDate, value: String?): Boolean {
        if (AlarmRules.isLocked(date, now())) return false
        val normalized = if (value == routineAlarm(date)) null else value
        ensureDay(date)
        days.setOverride(date.key(), normalized)
        // An unlocked day's frozen value is stale now; the scheduler rewrites it.
        days.setScheduled(date.key(), null)
        onAlarmInputsChanged()
        return true
    }

    suspend fun recordMorning(date: LocalDate, at: String, source: String) {
        ensureDay(date)
        days.setMorning(date.key(), at, source)
        onAlarmInputsChanged()
    }

    suspend fun clearMorning(date: LocalDate) {
        days.setMorning(date.key(), null, null)
        onAlarmInputsChanged()
    }

    // ---- Check-in ----

    /** 1..5, or null to clear. */
    suspend fun setMood(date: LocalDate, value: Int?) {
        ensureDay(date)
        days.setMood(date.key(), value?.coerceIn(1, 5))
        onRatingChanged(date)
    }

    suspend fun setEnergy(date: LocalDate, value: Int?) {
        ensureDay(date)
        days.setEnergy(date.key(), value?.coerceIn(1, 5))
        onRatingChanged(date)
    }

    suspend fun isRated(date: LocalDate): Boolean =
        days.get(date.key()).let { it?.mood != null && it.energy != null }

    // ---- Workout ----

    suspend fun lastTime(exerciseId: Long, before: LocalDate): LastTime? {
        val ref = workouts.lastTimeRef(exerciseId, before.key()) ?: return null
        return LastTime(LocalDate.parse(ref.date), workouts.filledSets(ref.loggedExerciseId))
    }

    /** New exercises get as many blank sets as last time, or 3 if never done. */
    private suspend fun addLogged(workoutId: Long, exerciseId: Long, position: Int, date: LocalDate) {
        val loggedId = workouts.insertLogged(LoggedExerciseEntity(workoutId = workoutId, exerciseId = exerciseId, position = position))
        val n = lastTime(exerciseId, date)?.sets?.size ?: 3
        workouts.insertSets((0 until n).map { ExerciseSetEntity(loggedExerciseId = loggedId, position = it) })
    }

    /** Copies the template into the day. From here on the day owns its workout. */
    suspend fun startWorkout(date: LocalDate, templateId: Long) = db.withTransaction {
        val template = routine.getTemplate(templateId) ?: return@withTransaction
        ensureDay(date)
        workouts.deleteWorkout(date.key())
        val workoutId = workouts.insertWorkout(DayWorkoutEntity(date = date.key(), name = template.name))
        routine.templateExerciseIds(templateId).forEachIndexed { i, exId -> addLogged(workoutId, exId, i, date) }
    }

    /** A workout with no exercises yet, for days that don't follow a saved one. */
    suspend fun startBlankWorkout(date: LocalDate) = db.withTransaction {
        ensureDay(date)
        workouts.deleteWorkout(date.key())
        workouts.insertWorkout(DayWorkoutEntity(date = date.key(), name = "Workout"))
    }

    suspend fun addExercise(date: LocalDate, name: String) = db.withTransaction {
        val workoutId = workouts.idFor(date.key()) ?: return@withTransaction
        addLogged(workoutId, exercises.getOrCreate(name.trim()), workouts.maxLoggedPosition(workoutId) + 1, date)
    }

    /** Adds a set that copies the one above it. */
    suspend fun addSet(loggedId: Long) {
        val sets = workouts.sets(loggedId)
        val prev = sets.lastOrNull()
        workouts.insertSets(listOf(ExerciseSetEntity(loggedExerciseId = loggedId, position = sets.size, weight = prev?.weight, reps = prev?.reps)))
    }

    suspend fun useLast(loggedId: Long, exerciseId: Long, date: LocalDate) = db.withTransaction {
        val last = lastTime(exerciseId, date) ?: return@withTransaction
        workouts.clearSets(loggedId)
        workouts.insertSets(last.sets.mapIndexed { i, s -> ExerciseSetEntity(loggedExerciseId = loggedId, position = i, weight = s.weight, reps = s.reps) })
    }

    suspend fun updateSet(id: Long, weight: Double?, reps: Int?) = workouts.updateSet(id, weight, reps)

    /** Removing an exercise's last set removes the exercise. */
    suspend fun deleteSet(setId: Long, loggedId: Long) = db.withTransaction {
        workouts.deleteSet(setId)
        if (workouts.sets(loggedId).isEmpty()) workouts.deleteLogged(loggedId)
    }

    /** Takes the exercise and all its sets out of the day's workout. */
    suspend fun removeExercise(loggedId: Long) = workouts.deleteLogged(loggedId)

    /** Swaps the exercise with its neighbor above or below; nothing at the ends. */
    suspend fun moveExercise(loggedId: Long, up: Boolean) = db.withTransaction {
        val logged = workouts.getLogged(loggedId) ?: return@withTransaction
        val all = workouts.loggedIn(logged.workoutId)
        val i = all.indexOfFirst { it.id == loggedId }
        val other = all.getOrNull(if (up) i - 1 else i + 1) ?: return@withTransaction
        workouts.setLoggedPosition(logged.id, other.position)
        workouts.setLoggedPosition(other.id, logged.position)
    }

    suspend fun removeWorkout(date: LocalDate) = workouts.deleteWorkout(date.key())
}
