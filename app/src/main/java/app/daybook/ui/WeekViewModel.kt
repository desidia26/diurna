package app.daybook.ui

import app.daybook.AppContainer
import app.daybook.domain.AlarmRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import java.time.DayOfWeek
import java.time.LocalDate

data class WeekDayUi(
    val date: LocalDate,
    val morning: AlarmRules.MorningResult,
    val scannedAt: String?,
    val workoutName: String?,
    val plannedName: String?,
    val kcal: Int?,
    val steps: Long? = null,
    val stepGoal: Int = 10_000,
)

data class WeekUi(val start: LocalDate, val today: LocalDate, val days: List<WeekDayUi>) {
    private val scored = days.filter { it.morning.scored }
    val onTime = scored.count { it.morning.status == AlarmRules.Morning.ON_TIME }
    val scoredCount = scored.size
}

@OptIn(ExperimentalCoroutinesApi::class)
class WeekViewModel(private val c: AppContainer) : ScreenViewModel() {
    val week: StateFlow<WeekUi?> = c.selectedDate.value.flatMapLatest { d ->
        val start = d.with(DayOfWeek.MONDAY)
        combine(
            combine(c.days.observeWeek(start), c.routine.observeRoutine(), c.routine.observeTemplates(), ::Triple),
            c.settings.stepGoal, minuteTicker,
        ) { (rows, routineDays, tpls), goal, now -> Triple(Triple(rows, routineDays, tpls), goal, now) }
            .mapLatest { (data, goal, now) ->
            val (rows, routineDays, tpls) = data
            WeekUi(start, now.toLocalDate(), rows.map { r ->
                val rd = routineDays.find { it.weekday == r.date.dayOfWeek.value }
                val alarm = AlarmRules.effective(r.date, now, r.day?.alarmOverride, rd?.alarmTime ?: "06:30", r.day?.scheduledAlarm)
                WeekDayUi(
                    date = r.date,
                    morning = AlarmRules.morning(r.date, now, alarm, r.day?.scannedAt),
                    scannedAt = r.day?.scannedAt,
                    workoutName = r.workoutName,
                    // A finished day keeps the workout it had planned, even if the routine changed since.
                    plannedName = if (r.day?.finalized == true) r.day.plannedWorkout else tpls.find { it.id == rd?.templateId }?.name,
                    kcal = r.kcal,
                    // Finished days use their frozen count; today reads live; future days have none.
                    steps = r.day?.steps ?: if (r.date == now.toLocalDate()) c.steps.stepsOn(r.date) else null,
                    stepGoal = goal,
                )
            })
        }
    }.state(null)

    fun shift(days: Long) = c.selectedDate.shift(days)
    fun goTo(d: LocalDate) = c.selectedDate.goTo(d)
}
