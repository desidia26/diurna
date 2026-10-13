package app.daybook.wallpaper

import app.daybook.data.AppDatabase
import app.daybook.data.DayRepository
import app.daybook.data.key
import app.daybook.data.totals
import app.daybook.domain.Targets
import app.daybook.domain.AlarmRules
import app.daybook.domain.DayText
import app.daybook.domain.AlarmRules.Morning
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
        val total = workout.exercises.size
        val done = workout.exercises.count { e -> e.sets.any { it.reps != null } }
        workoutDone = total > 0 && done == total
        workoutLine = DayText.workout(workout.workout.name, done, total)
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

    return LockSnapshot(today, morningLine, tone, workoutLine, workoutDone, foodLine, week, tomorrowLine)
}
