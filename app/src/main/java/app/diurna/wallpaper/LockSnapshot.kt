package app.diurna.wallpaper

import app.diurna.data.AppDatabase
import app.diurna.data.DayRepository
import app.diurna.data.key
import app.diurna.data.totals
import app.diurna.domain.Targets
import app.diurna.domain.AlarmRules
import app.diurna.domain.DayText
import app.diurna.domain.AlarmRules.Morning
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Everything the lock screen shows, as plain text and states, so drawing stays dumb. */
data class LockSnapshot(
    val date: LocalDate,
    val morningLine: String,
    val morningTone: Tone,
    val workoutLine: String,
    val workoutDone: Boolean,
    val foodLine: String,
    val stepsLine: String?,
    val stepsReached: Boolean,
    val week: List<Pair<LocalDate, Morning>>,
    val tomorrowLine: String?,
) {
    enum class Tone { Good, Warn, Neutral }
}

suspend fun buildSnapshot(
    db: AppDatabase,
    days: DayRepository,
    now: LocalDateTime = LocalDateTime.now(),
    targets: Targets? = null,
    steps: Long? = null,
    stepGoal: Int = 10_000,
): LockSnapshot {
    val today = now.toLocalDate()
    val day = db.dayDao().get(today.key())
    val alarm = days.effectiveAlarm(today)
    val morning = AlarmRules.morning(today, now, alarm, day?.scannedAt)
    val morningLine = DayText.morning(morning, alarm, day?.scannedAt)
    val tone = when (morning.status) {
        Morning.ON_TIME -> LockSnapshot.Tone.Good
        Morning.LATE, Morning.WAITING, Morning.MISSED -> LockSnapshot.Tone.Warn
        else -> LockSnapshot.Tone.Neutral
    }

    val workout = db.workoutDao().observe(today.key()).first()
    val workoutLine: String
    var workoutDone = false
    if (workout != null) {
        val lines = workout.exercises.sortedBy { it.logged.position }.map { e ->
            val pieces = e.sets.filter { it.isDone }
            val one = pieces.singleOrNull()
            DayText.ExerciseLine(
                name = e.exercise.name,
                cardio = e.exercise.isCardio,
                done = pieces.isNotEmpty(),
                distanceM = pieces.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum(),
                durationS = pieces.mapNotNull { it.durationS }.takeIf { it.isNotEmpty() }?.sum(),
                // The machine's own split for a single piece; intervals' split is worked out from the totals.
                splitS = one?.splitS,
                kcal = pieces.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum(),
                style = e.exercise.style,
            )
        }
        workoutDone = lines.isNotEmpty() && lines.all { it.done }
        workoutLine = DayText.workout(workout.workout.name, lines)
    } else {
        val routineDay = db.routineDao().getDay(today.dayOfWeek.value)
        val template = routineDay?.templateId?.let { db.routineDao().getTemplate(it) }
        workoutLine = DayText.plannedWorkout(template?.name)
    }

    val food = db.foodDao().observe(today.key()).first()
    // Calories and macros once anything is estimated; until then, just what was logged.
    val foodLine = DayText.nutrition(food.totals(), targets) ?: DayText.food(food.map { it.time })

    val monday = today.with(DayOfWeek.MONDAY)
    val week = (0L..6L).map { i ->
        val d = monday.plusDays(i)
        d to AlarmRules.morning(d, now, days.effectiveAlarm(d), db.dayDao().get(d.key())?.scannedAt).status
    }

    // After the lock, tomorrow's alarm is set in stone; worth seeing before bed.
    val tomorrow = today.plusDays(1)
    val tomorrowLine = if (AlarmRules.isLocked(tomorrow, now)) {
        val t = days.effectiveAlarm(tomorrow)
        if (t == AlarmRules.OFF) "Tomorrow · no alarm" else "Tomorrow · $t · locked"
    } else null

    return LockSnapshot(
        today, morningLine, tone, workoutLine, workoutDone, foodLine,
        DayText.steps(steps, stepGoal), steps != null && steps >= stepGoal, week, tomorrowLine,
    )
}
