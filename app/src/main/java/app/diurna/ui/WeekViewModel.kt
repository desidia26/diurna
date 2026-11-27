package app.diurna.ui

import app.diurna.AppContainer
import app.diurna.domain.AlarmRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import app.diurna.domain.SleepRules
import app.diurna.domain.SleepView
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
    /** Asleep in the night ending that morning; null with sleep off or no data. */
    val sleep: String? = null,
)

data class WeekUi(val start: LocalDate, val today: LocalDate, val days: List<WeekDayUi>, val deload: Boolean = false) {
    private val scored = days.filter { it.morning.scored }
    val onTime = scored.count { it.morning.status == AlarmRules.Morning.ON_TIME }
    val scoredCount = scored.size
}

@OptIn(ExperimentalCoroutinesApi::class)
class WeekViewModel(private val c: AppContainer) : ScreenViewModel(c.actions) {
    /** Hours asleep by wake day, for the week holding [d]; empty while sleep is off. */
    private fun sleepFor(d: LocalDate): Flow<Map<LocalDate, String>> {
        val start = d.with(DayOfWeek.MONDAY)
        return c.settings.sleep.flatMapLatest { on ->
            if (!on) flowOf(emptyMap())
            else combine(c.sleep.observe(start, start.plusDays(6)), c.settings.sleepOrigin, c.sleep.observeOrigins(start.minusDays(30))) { rows, chosen, origins ->
                val origin = SleepRules.pickOrigin(chosen, origins.map { it.origin to it.nights })
                (0L..6L).map { start.plusDays(it) }
                    .mapNotNull { day -> SleepView.chip(SleepView.day(rows, day, origin))?.let { day to it } }
                    .toMap()
            }
        }
    }

    val week: StateFlow<WeekUi?> = c.selectedDate.value.flatMapLatest { d -> weekOf(d).combine(sleepFor(d)) { ui, sleep ->
        ui.copy(days = ui.days.map { it.copy(sleep = sleep[it.date]) })
    } }.state(null)

    private fun weekOf(d: LocalDate): Flow<WeekUi> = run {
        val start = d.with(DayOfWeek.MONDAY)
        combine(
            combine(c.days.observeWeek(start), c.routine.observeRoutine(), c.routine.observeTemplates(), ::Triple),
            c.settings.stepGoal, minuteTicker, c.days.observeDeload(start),
        ) { (rows, routineDays, tpls), goal, now, deload -> Triple(Triple(rows, routineDays, tpls), goal, now) to deload }
            .mapLatest { (t, deload) ->
            val (data, goal, now) = t
            val (rows, routineDays, tpls) = data
            WeekUi(start, now.toLocalDate(), deload = deload, days = rows.map { r ->
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
    }

    fun setDeload(on: Boolean) = io("week.set_deload") { c.days.setDeload(c.selectedDate.value.value, on) }

    fun shift(days: Long) = c.selectedDate.shift(days)
    fun goTo(d: LocalDate) = c.selectedDate.goTo(d)
}
