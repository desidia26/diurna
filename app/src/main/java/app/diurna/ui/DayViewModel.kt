package app.diurna.ui

import android.net.Uri
import app.diurna.debug.operation
import app.diurna.AppContainer
import app.diurna.data.LibraryRepository
import app.diurna.ai.CardioReading
import app.diurna.domain.CardioPiece
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
import app.diurna.domain.DailyMetrics
import app.diurna.domain.Metrics
import app.diurna.domain.WorkoutFold
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.diurna.domain.NutritionTotals
import app.diurna.domain.Targets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewModelScope
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
    /** The selected day's week is marked as a deload. */
    val deload: Boolean = false,
) {
    val totals: NutritionTotals get() = food.totals()
    val plannedTemplate: Template? get() =
        if (day?.finalized == true) templates.find { it.name == day.plannedWorkout }
        else templates.find { it.id == routineDay?.templateId }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(private val c: AppContainer) : ScreenViewModel(c.actions), PhotoImportHost {
    /** Chosen AI for food estimates and cardio photo reads; shown in the Log-from-photo disclosure. */
    val aiProvider = c.aiProvider.selected

    private val date get() = c.selectedDate.value.value

    val day: StateFlow<DayUi?> = c.selectedDate.value.flatMapLatest { d ->
        // Marking any week a deload can change "last time", so hints follow the deload table too.
        val workoutWithLast = combine(c.days.observeWorkout(d), c.days.observeDeloadMondays(), ::Pair).mapLatest { (w, deloads) ->
            Triple(w, w?.exercises.orEmpty().sortedBy { it.logged.position }.map {
                ExerciseUi(it.copy(sets = it.sets.sortedBy { s -> s.position }), c.days.lastTime(it.exercise.id, d))
            }, Metrics.weekOf(d) in deloads)
        }
        combine(
            combine(c.days.observeDay(d), c.routine.observeRoutine(), c.routine.observeTemplates(), ::Triple),
            workoutWithLast,
            c.food.observe(d),
            c.nutrition.observeTargets(d),
            minuteTicker,
        ) { (day, routineDays, tpls), (workout, exercises, deload), food, targets, now ->
            val rd = routineDays.find { it.weekday == d.dayOfWeek.value }
            val alarm = AlarmRules.effective(d, now, day?.alarmOverride, rd?.alarmTime ?: "06:30", day?.scheduledAlarm)
            DayUi(
                date = d, today = now.toLocalDate(), day = day, routineDay = rd, templates = tpls,
                workout = workout, exercises = exercises, food = food, alarm = alarm,
                locked = AlarmRules.isLocked(d, now),
                morning = AlarmRules.morning(d, now, alarm, day?.scannedAt),
                targets = targets,
                deload = deload,
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
    /** Called on arriving at a day (opening the tab or changing the date); a finished workout opens folded. */
    fun arrive(ui: DayUi) {
        folded.value = WorkoutFold.onArrival(folded.value, ui.exercises.map { it.logged.sets })
    }

    // Navigation
    fun shift(days: Long) = c.selectedDate.shift(days)
    fun goTo(d: LocalDate) = c.selectedDate.goTo(d)

    // Morning
    fun setOverride(value: String?) = io("day.set_override") { c.days.setAlarmOverride(date, value) }
    fun logManualMorning(time: String) = io("day.log_manual_morning") { c.days.recordMorning(date, time, "manual") }
    fun setBodyWeight(lb: Double?) = io("day.set_body_weight") { c.nutrition.setBodyWeight(date, lb) }
    fun clearMorning() = io("day.clear_morning") { c.days.clearMorning(date) }

    // Check-in
    data class CheckInUi(val metrics: List<DailyMetrics.Def>, val values: Map<Long, DailyMetrics.Value>) {
        val done: Boolean get() = DailyMetrics.complete(metrics, values)
        val summary: String get() = DailyMetrics.summary(metrics, values)
    }

    val checkIn: StateFlow<CheckInUi?> = c.selectedDate.value.flatMapLatest { d ->
        combine(c.dailyMetrics.definitions.map { all -> all.filter { !it.archived } }, c.dailyMetrics.observeValues(d), ::CheckInUi)
    }.state(null)

    // Text saves on every keystroke; the lock is first-come-first-served, so writes land in typing order.
    private val checkInWrites = Mutex()
    fun setMetric(id: Long, value: Double?) { val d = date; io("day.set_metric") { checkInWrites.withLock { c.dailyMetrics.setValue(d, id, value) } } }
    fun setMetricText(id: Long, text: String) { val d = date; io("day.set_metric_text") { checkInWrites.withLock { c.dailyMetrics.setText(d, id, text) } } }

    // Workout
    fun startWorkout(templateId: Long) = io("day.start_workout") { c.days.startWorkout(date, templateId) }
    fun startBlankWorkout() = io("day.start_blank_workout") { c.days.startBlankWorkout(date) }
    fun addExercise(name: String) = io("day.add_exercise") { if (name.isNotBlank()) c.days.addExercise(date, name) }
    fun addSet(loggedId: Long) = io("day.add_set") { c.days.addSet(loggedId) }
    fun useLast(loggedId: Long, exerciseId: Long) = io("day.use_last") { c.days.useLast(loggedId, exerciseId, date) }
    fun updateSet(id: Long, weight: Double?, reps: Int?) = io("day.update_set") { c.days.updateSet(id, weight, reps) }
    fun updatePiece(id: Long, piece: CardioPiece) = io("day.update_piece") { c.days.updatePiece(id, piece) }

    /**
     * Reads a cardio machine's summary screen from an imported photo for the confirm step. The photo is only
     * needed for the call, so it's deleted straight after reading. Nothing is saved here.
     */
    suspend fun readCardioPhoto(name: String): CardioPhotoResult = withContext(Dispatchers.IO) {
        PhotoAiReads.readCardioPhoto(name, c.foodPhotos, c.cardioReader, c.supportsImages())
    }

    /** Reads a Nutrition Facts label into meal fields for confirm-before-save. Temp photo deleted after. */
    suspend fun readLabelPhoto(name: String): LabelPhotoResult = withContext(Dispatchers.IO) {
        PhotoAiReads.readLabelPhoto(name, c.foodPhotos, c.labelReader)
    }
    fun deleteSet(setId: Long, loggedId: Long) = io("day.delete_set") { c.days.deleteSet(setId, loggedId) }
    fun removeWorkout() = io("day.remove_workout") { c.days.removeWorkout(date) }
    fun removeExercise(loggedId: Long) = io("day.remove_exercise") { c.days.removeExercise(loggedId) }
    fun moveExercise(loggedId: Long, up: Boolean) = io("day.move_exercise") { c.days.moveExercise(loggedId, up) }
    fun setExerciseNote(exerciseId: Long, note: String) = io("day.set_exercise_note") { c.library.setExerciseNote(exerciseId, note) }

    // Food
    fun addFood(text: String, photo: String? = null) = io("day.add_food") { c.actions.dispatch(app.diurna.debug.Action.Text(app.diurna.debug.Screen.DAY, app.diurna.debug.TextField.FOOD, text)); if (text.isNotBlank()) c.food.add(date, text, photo) }
    fun addFood(text: String, photos: List<String>) = io("day.add_food") { c.actions.dispatch(app.diurna.debug.Action.Text(app.diurna.debug.Screen.DAY, app.diurna.debug.TextField.FOOD, text)); if (text.isNotBlank()) c.food.add(date, text, photos) }
    // Deleting hides the entry for a few seconds first, so the card can offer Undo.
    private val foodDeletes = PendingDelete<Long>(viewModelScope) { id ->
        val event = app.diurna.debug.Action.Event(app.diurna.debug.Screen.DAY, "day.delete_food")
        try {
            withContext(Dispatchers.IO) { c.food.delete(id) }
            c.actions.dispatch(event.copy(result = app.diurna.debug.Action.Result.OK))
        } catch (e: Exception) {
            c.actions.dispatch(event.copy(result = app.diurna.debug.Action.Result.FAILED)); throw e
        }
    }
    val hiddenFood = foodDeletes.hidden
    val undoableFood = foodDeletes.undoable
    fun deleteFood(id: Long) = foodDeletes.start(id)
    fun undoDeleteFood() = foodDeletes.undo()
    fun editFood(id: Long, text: String, time: String, photo: String?) = io("day.edit_food") { c.actions.dispatch(app.diurna.debug.Action.Text(app.diurna.debug.Screen.DAY, app.diurna.debug.TextField.FOOD, text)); c.food.edit(id, text, time, photo) }
    fun editFood(id: Long, text: String, time: String, photos: List<String>) = io("day.edit_food") {
        c.actions.dispatch(app.diurna.debug.Action.Text(app.diurna.debug.Screen.DAY, app.diurna.debug.TextField.FOOD, text))
        app.diurna.debug.FoodEditLogging.logSave(c.actions, before = c.food.gallery(id), after = photos)
        c.food.edit(id, text, time, photos)
    }
    fun logEditOpened(photos: Int) = app.diurna.debug.FoodEditLogging.logOpen(c.actions, photos)
    fun entryPhotos(entryId: Long) = c.food.observePhotos(entryId).map { rows -> rows.map { it.fileName } }

    val photos get() = c.foodPhotos
    /** False when Setup says the selected model has no vision; photo food falls back to text. */
    fun supportsImages(): Boolean = c.supportsImages()
    override val captureFile get() = c.photoImporter.captureFile
    override suspend fun importPhoto(uri: Uri) = c.photoImporter.import(uri)
    override suspend fun importCapture() = c.photoImporter.importCapture()
    /** A photo attached and then removed, replaced or abandoned before it was saved with an entry. */
    fun discardPhoto(name: String?) = io("day.discard_photo") { withContext(Dispatchers.IO) { c.foodPhotos.delete(name) } }
    fun retryEstimates() = io("day.retry_estimates") { c.food.retryFailedEstimates() }

    // Saved meals
    val meals = c.library.observeMeals().state(emptyList())
    val detailedNutrition = c.settings.detailedNutrition.state(false)
    fun logMeal(mealId: Long, servings: Double = 1.0, photo: String? = null) =
        io("day.log_meal") { c.library.logMeal(date, mealId, servings, photo = photo) }
    fun logMeal(mealId: Long, servings: Double, photos: List<String>) =
        io("day.log_meal") { c.library.logMeal(date, mealId, servings, photos = photos) }
    fun logMealByGrams(mealId: Long, grams: Double, photo: String? = null) =
        io("day.log_meal_by_grams") { c.library.logMealByGrams(date, mealId, grams, photo = photo) }
    fun logMealByGrams(mealId: Long, grams: Double, photos: List<String>) =
        io("day.log_meal_by_grams") { c.library.logMealByGrams(date, mealId, grams, photos = photos) }
    suspend fun scanBarcode(code: String, upcE: Boolean) = c.debug.operation("barcode.lookup", app.diurna.debug.Screen.DAY) {
        c.debug.registerAttachment(code.toByteArray(), app.diurna.debug.AttachmentKind.BARCODE)
        c.library.scan(code, upcE)
    }
    suspend fun saveMeal(meal: MealEntity) = c.library.saveMeal(meal)
    /**
     * Meal from photo: the ingredients confirmed from one photo become one entry on this day, and
     * with [saveToLibrary] a Library recipe too. Debug log: counts and the outcome only.
     */
    suspend fun logMealFromPhoto(name: String, lines: List<IngredientDraft>, saveToLibrary: Boolean): LibraryRepository.Result {
        val start = System.nanoTime()
        val r = c.library.logRecipe(date, name, lines.mapIndexed { i, d -> d.toEntity(0, i) }, saveToLibrary)
        c.actions.dispatch(app.diurna.debug.Action.Event(
            app.diurna.debug.Screen.DAY, "day.meal_from_photo",
            result = if (r == LibraryRepository.Result.Ok) app.diurna.debug.Action.Result.OK else app.diurna.debug.Action.Result.FAILED,
            latencyMs = (System.nanoTime() - start) / 1_000_000,
            details = mapOf("ingredients" to lines.size.toString(), "save_to_library" to saveToLibrary.toString(),
                "outcome" to if (r == LibraryRepository.Result.Ok) "logged" else "problem"),
        ))
        return r
    }
    suspend fun mealByBarcode(code: String) = c.library.mealByBarcode(code)

    // A recipe link typed in the food box: the Library's import, its recipe editor, then servings.
    val hasFdcKey = c.usdaKey.lastFour.map { it != null }.state(false)

    /** Hardened HTTPS fetch + schema.org Recipe parse ([app.diurna.data.recipe.RecipeImporter]); no AI, no keys. */
    suspend fun importRecipeFromUrl(url: String): app.diurna.data.recipe.RecipeImportResult =
        c.dayRecipeImporter.importFromUrl(url)
    suspend fun searchFdc(query: String) = c.fdcClient.search(query)
    suspend fun loadFdcNutrition(fdcId: Int) = c.fdcClient.nutritionProfile(fdcId)
    suspend fun resolveDensities(lines: List<app.diurna.data.RecipeIngredientEntity>) =
        app.diurna.data.density.RecipeLookups.mapParallel(lines) { c.densityResolver.enrichLine(it) }
    /** Saves the confirmed recipe to the Library and returns it, so the servings step can log it. */
    suspend fun saveImportedRecipe(meal: MealEntity, lines: List<app.diurna.data.RecipeIngredientEntity>) =
        c.library.saveRecipeReturning(meal, lines)

    /** Recipe editor debug events (lookup, a Save that waited or was stopped): fixed tokens and counts only. */
    fun logRecipeEditor(name: String, details: Map<String, String>, latencyMs: Long?) =
        c.actions.dispatch(app.diurna.debug.Action.Event(app.diurna.debug.Screen.DAY, name,
            result = app.diurna.debug.Action.Result.OK, latencyMs = latencyMs, details = details))

    /** Debug log for the food box's link route: a fixed [outcome] token only, never the link or text. */
    fun logFoodLink(outcome: String) = c.actions.dispatch(app.diurna.debug.Action.Event(
        app.diurna.debug.Screen.DAY, "day.food_link", result = app.diurna.debug.Action.Result.OK,
        details = mapOf("outcome" to outcome),
    ))
    fun setServings(entryId: Long, servings: Double) = io("day.set_servings") { c.library.setServings(entryId, servings) }
    suspend fun saveEntryAsMeal(entryId: Long, meal: MealEntity) = c.library.saveEntryAsMeal(entryId, meal)
}
