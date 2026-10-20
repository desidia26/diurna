package app.daybook.ui

import app.daybook.AppContainer
import app.daybook.data.DayEntity
import app.daybook.data.FoodEntryEntity
import app.daybook.data.LastTime
import app.daybook.data.MealEntity
import app.daybook.data.LoggedExerciseWithSets
import app.daybook.data.RoutineDayEntity
import app.daybook.data.Template
import app.daybook.data.WorkoutWithExercises
import app.daybook.data.totals
import app.daybook.domain.AlarmRules
import app.daybook.domain.NutritionTotals
import app.daybook.domain.Targets
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import java.time.LocalDate

data class ExerciseUi(val logged: LoggedExerciseWithSets, val last: LastTime?)

data class DayUi(
    val date: LocalDate,
    val today: LocalDate,
    val day: DayEntity?,
    val routineDay: RoutineDayEntity?,
    val templates: List<Template>,
    val workout: WorkoutWithExercises?,
    val exercises: List<ExerciseUi>,
    val food: List<FoodEntryEntity>,
    val alarm: String,
    val locked: Boolean,
    val morning: AlarmRules.MorningResult,
    val targets: Targets?,
) {
    val totals: NutritionTotals get() = food.totals()
    val plannedTemplate: Template? get() =
        if (day?.finalized == true) templates.find { it.name == day.plannedWorkout }
        else templates.find { it.id == routineDay?.templateId }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(private val c: AppContainer) : ScreenViewModel() {
    private val date get() = c.selectedDate.value.value

    val day: StateFlow<DayUi?> = c.selectedDate.value.flatMapLatest { d ->
        val workoutWithLast = c.days.observeWorkout(d).mapLatest { w ->
            w to (w?.exercises.orEmpty().sortedBy { it.logged.position }.map {
                ExerciseUi(it.copy(sets = it.sets.sortedBy { s -> s.position }), c.days.lastTime(it.exercise.id, d))
            })
        }
        combine(
            combine(c.days.observeDay(d), c.routine.observeRoutine(), c.routine.observeTemplates(), ::Triple),
            workoutWithLast,
            c.food.observe(d),
            c.nutrition.observeTargets(d),
            minuteTicker,
        ) { (day, routineDays, tpls), (workout, exercises), food, targets, now ->
            val rd = routineDays.find { it.weekday == d.dayOfWeek.value }
            val alarm = AlarmRules.effective(d, now, day?.alarmOverride, rd?.alarmTime ?: "06:30", day?.scheduledAlarm)
            DayUi(
                date = d, today = now.toLocalDate(), day = day, routineDay = rd, templates = tpls,
                workout = workout, exercises = exercises, food = food, alarm = alarm,
                locked = AlarmRules.isLocked(d, now),
                morning = AlarmRules.morning(d, now, alarm, day?.scannedAt),
                targets = targets,
            )
        }
    }.state(null)

    /**
     * Steps for the selected day: the frozen count once a day is finalized, otherwise read from
     * the step source each minute. Null count means no data.
     */
    data class StepsUi(val count: Long?, val goal: Int)

    val steps: StateFlow<StepsUi?> = c.selectedDate.value.flatMapLatest { d ->
        combine(c.days.observeDay(d), c.settings.stepGoal, minuteTicker) { day, goal, now -> Triple(day, goal, now) }
            .mapLatest { (day, goal, now) ->
                val count = when {
                    day?.finalized == true -> day.steps
                    d.isAfter(now.toLocalDate()) -> null
                    else -> c.steps.stepsOn(d)
                }
                StepsUi(count, goal)
            }
    }.state(null)

    val armed = c.scheduler.state
    val online = c.network.online
    val exerciseNames = c.routine.observeExerciseNames().state(emptyList())

    /** Cards folded down to their summary, by section; kept while the app is open. */
    val folded = MutableStateFlow(emptySet<String>())
    fun toggleFold(section: String) {
        folded.value = folded.value.let { if (section in it) it - section else it + section }
    }

    // Navigation
    fun shift(days: Long) = c.selectedDate.shift(days)
    fun goTo(d: LocalDate) = c.selectedDate.goTo(d)

    // Morning
    fun setOverride(value: String?) = io { c.days.setAlarmOverride(date, value) }
    fun logManualMorning(time: String) = io { c.days.recordMorning(date, time, "manual") }
    fun setBodyWeight(lb: Double?) = io { c.nutrition.setBodyWeight(date, lb) }
    fun clearMorning() = io { c.days.clearMorning(date) }

    // Check-in
    fun setMood(value: Int?) = io { c.days.setMood(date, value) }
    fun setEnergy(value: Int?) = io { c.days.setEnergy(date, value) }

    // Workout
    fun startWorkout(templateId: Long) = io { c.days.startWorkout(date, templateId) }
    fun startBlankWorkout() = io { c.days.startBlankWorkout(date) }
    fun addExercise(name: String) = io { if (name.isNotBlank()) c.days.addExercise(date, name) }
    fun addSet(loggedId: Long) = io { c.days.addSet(loggedId) }
    fun useLast(loggedId: Long, exerciseId: Long) = io { c.days.useLast(loggedId, exerciseId, date) }
    fun updateSet(id: Long, weight: Double?, reps: Int?) = io { c.days.updateSet(id, weight, reps) }
    fun deleteSet(setId: Long, loggedId: Long) = io { c.days.deleteSet(setId, loggedId) }
    fun removeWorkout() = io { c.days.removeWorkout(date) }
    fun removeExercise(loggedId: Long) = io { c.days.removeExercise(loggedId) }
    fun moveExercise(loggedId: Long, up: Boolean) = io { c.days.moveExercise(loggedId, up) }

    // Food
    fun addFood(text: String) = io { if (text.isNotBlank()) c.food.add(date, text) }
    fun deleteFood(id: Long) = io { c.food.delete(id) }
    fun editFood(id: Long, text: String, time: String) = io { c.food.edit(id, text, time) }
    fun retryEstimates() = io { c.food.retryFailedEstimates() }

    // Saved meals
    val meals = c.library.observeMeals().state(emptyList())
    fun logMeal(mealId: Long, servings: Double = 1.0) = io { c.library.logMeal(date, mealId, servings) }
    suspend fun scanBarcode(code: String, upcE: Boolean) = c.library.scan(code, upcE)
    suspend fun saveMeal(meal: MealEntity) = c.library.saveMeal(meal)
    suspend fun mealByBarcode(code: String) = c.library.mealByBarcode(code)
    fun setServings(entryId: Long, servings: Double) = io { c.library.setServings(entryId, servings) }
    suspend fun saveEntryAsMeal(entryId: Long, meal: MealEntity) = c.library.saveEntryAsMeal(entryId, meal)
}
