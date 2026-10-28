package app.diurna.ui

import android.net.Uri
import app.diurna.AppContainer
import app.diurna.ai.CardioReading
import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.NoApiKey
import app.diurna.domain.CardioPiece
import kotlinx.coroutines.CancellationException
import app.diurna.data.DayEntity
import app.diurna.data.FoodEntryEntity
import app.diurna.data.LastTime
import app.diurna.data.MealEntity
import app.diurna.data.LoggedExerciseWithSets
import app.diurna.data.RoutineDayEntity
import app.diurna.data.Template
import app.diurna.data.WorkoutWithExercises
import app.diurna.data.totals
import app.diurna.domain.AlarmRules
import app.diurna.domain.NutritionTotals
import app.diurna.domain.Targets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.time.LocalDate

sealed interface CardioPhotoResult {
    data class Read(val reading: CardioReading) : CardioPhotoResult
    data class Problem(val message: String) : CardioPhotoResult
}

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
class DayViewModel(private val c: AppContainer) : ScreenViewModel(), PhotoImportHost {
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
    val exerciseNames = c.settings.cardio.flatMapLatest { c.routine.observeExerciseNames(it) }.state(emptyList())

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
    fun updatePiece(id: Long, piece: CardioPiece) = io { c.days.updatePiece(id, piece) }

    /**
     * Reads a cardio machine's summary screen from an imported photo for the confirm step. The photo is only
     * needed for the call, so it's deleted straight after reading. Nothing is saved here.
     */
    suspend fun readCardioPhoto(name: String): CardioPhotoResult = withContext(Dispatchers.IO) {
        val bytes = c.foodPhotos.read(name)
        c.foodPhotos.delete(name)
        if (bytes == null) return@withContext CardioPhotoResult.Problem("Couldn't read that photo")
        try {
            CardioPhotoResult.Read(c.cardioReader.read(bytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoApiKey) {
            CardioPhotoResult.Problem(e.message ?: "Add an API key in Setup to read photos")
        } catch (e: EstimateFailed) {
            CardioPhotoResult.Problem(e.message ?: "Couldn't read the screen")
        } catch (e: EstimateRetryable) {
            CardioPhotoResult.Problem("Couldn't reach the AI. Check your connection and try again.")
        } catch (e: Exception) {
            CardioPhotoResult.Problem("Couldn't read the screen (${e.javaClass.simpleName})")
        }
    }

    /** Reads a Nutrition Facts label into meal fields for confirm-before-save. Temp photo deleted after. */
    suspend fun readLabelPhoto(name: String): LabelPhotoResult = withContext(Dispatchers.IO) {
        val bytes = c.foodPhotos.read(name)
        c.foodPhotos.delete(name)
        if (bytes == null) return@withContext LabelPhotoResult.Problem("Couldn't read that photo")
        try {
            LabelPhotoResult.Read(c.labelReader.read(bytes))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoApiKey) {
            LabelPhotoResult.Problem(e.message ?: "Add an API key in Setup to read labels")
        } catch (e: EstimateFailed) {
            LabelPhotoResult.Problem(e.message ?: "Couldn't read the label")
        } catch (e: EstimateRetryable) {
            LabelPhotoResult.Problem("Couldn't reach the AI. Check your connection and try again.")
        } catch (e: Exception) {
            LabelPhotoResult.Problem("Couldn't read the label (${e.javaClass.simpleName})")
        }
    }
    fun deleteSet(setId: Long, loggedId: Long) = io { c.days.deleteSet(setId, loggedId) }
    fun removeWorkout() = io { c.days.removeWorkout(date) }
    fun removeExercise(loggedId: Long) = io { c.days.removeExercise(loggedId) }
    fun moveExercise(loggedId: Long, up: Boolean) = io { c.days.moveExercise(loggedId, up) }

    // Food
    fun addFood(text: String, photo: String? = null) = io { if (text.isNotBlank()) c.food.add(date, text, photo) }
    fun addFood(text: String, photos: List<String>) = io { if (text.isNotBlank()) c.food.add(date, text, photos) }
    fun deleteFood(id: Long) = io { c.food.delete(id) }
    fun editFood(id: Long, text: String, time: String, photo: String?) = io { c.food.edit(id, text, time, photo) }
    fun editFood(id: Long, text: String, time: String, photos: List<String>) = io { c.food.edit(id, text, time, photos) }
    fun entryPhotos(entryId: Long) = c.food.observePhotos(entryId).map { rows -> rows.map { it.fileName } }

    val photos get() = c.foodPhotos
    override val captureFile get() = c.photoImporter.captureFile
    override suspend fun importPhoto(uri: Uri) = c.photoImporter.import(uri)
    override suspend fun importCapture() = c.photoImporter.importCapture()
    /** A photo attached and then removed, replaced or abandoned before it was saved with an entry. */
    fun discardPhoto(name: String?) = io { withContext(Dispatchers.IO) { c.foodPhotos.delete(name) } }
    fun retryEstimates() = io { c.food.retryFailedEstimates() }

    // Saved meals
    val meals = c.library.observeMeals().state(emptyList())
    val detailedNutrition = c.settings.detailedNutrition.state(false)
    fun logMeal(mealId: Long, servings: Double = 1.0, photo: String? = null) =
        io { c.library.logMeal(date, mealId, servings, photo = photo) }
    fun logMeal(mealId: Long, servings: Double, photos: List<String>) =
        io { c.library.logMeal(date, mealId, servings, photos = photos) }
    fun logMealByGrams(mealId: Long, grams: Double, photo: String? = null) =
        io { c.library.logMealByGrams(date, mealId, grams, photo = photo) }
    fun logMealByGrams(mealId: Long, grams: Double, photos: List<String>) =
        io { c.library.logMealByGrams(date, mealId, grams, photos = photos) }
    suspend fun scanBarcode(code: String, upcE: Boolean) = c.library.scan(code, upcE)
    suspend fun saveMeal(meal: MealEntity) = c.library.saveMeal(meal)
    suspend fun mealByBarcode(code: String) = c.library.mealByBarcode(code)
    fun setServings(entryId: Long, servings: Double) = io { c.library.setServings(entryId, servings) }
    suspend fun saveEntryAsMeal(entryId: Long, meal: MealEntity) = c.library.saveEntryAsMeal(entryId, meal)
}
