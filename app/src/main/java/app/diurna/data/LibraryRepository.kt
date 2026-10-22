package app.diurna.data

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** Saved meals and the exercise catalog: the things set up once in Setup → Library. */
class LibraryRepository(
    private val db: AppDatabase,
    private val products: ProductSource = OpenFoodFacts(),
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val meals = db.mealDao()
    private val food = db.foodDao()
    private val exercises = db.exerciseDao()

    sealed interface Result {
        data object Ok : Result
        data class Problem(val message: String) : Result
    }

    // ---- Meals ----

    fun observeMeals(): Flow<List<MealEntity>> = meals.observeByUse()

    /** Adds a meal, or updates it when [meal] has an id. Past entries keep the numbers they were logged with. */
    suspend fun saveMeal(meal: MealEntity): Result = when (val v = validate(meal)) {
        is Validated.Bad -> v.problem
        is Validated.Good -> {
            if (meal.id == 0L) meals.insert(v.meal) else meals.update(v.meal)
            Result.Ok
        }
    }

    private sealed interface Validated {
        data class Good(val meal: MealEntity) : Validated
        data class Bad(val problem: Result.Problem) : Validated
    }

    private suspend fun validate(meal: MealEntity): Validated {
        val name = meal.name.trim()
        if (name.isEmpty()) return Validated.Bad(Result.Problem("Give the meal a name."))
        if (meals.nameTakenByOther(name, meal.id) > 0) return Validated.Bad(Result.Problem("You already have a meal called \"$name\"."))
        if (meal.barcode != null && meals.barcodeTakenByOther(meal.barcode, meal.id) > 0) {
            return Validated.Bad(Result.Problem("Another meal already has this barcode."))
        }
        return Validated.Good(meal.copy(name = name, note = meal.note?.trim()?.ifEmpty { null }))
    }

    suspend fun deleteMeal(id: Long) = meals.delete(id)

    // ---- Barcodes ----

    sealed interface ScanResult {
        /** Already in the library: log it. */
        data class Known(val meal: MealEntity) : ScanResult
        /** Found online: an unsaved meal, barcode set, for a check before saving. */
        data class Found(val draft: MealEntity) : ScanResult
        data object NotFound : ScanResult
        data class Failed(val message: String) : ScanResult
    }

    /** The library first (works offline), then Open Food Facts. */
    suspend fun scan(raw: String, upcE: Boolean = false): ScanResult {
        val code = normalizeBarcode(raw, upcE) ?: return ScanResult.NotFound
        meals.byBarcode(code)?.let { return ScanResult.Known(it) }
        val json = try {
            products.fetch(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ScanResult.Failed("Couldn't reach Open Food Facts. Check your connection.")
        }
        // Unparseable, implausible or calorie-less answers all count as not found.
        val draft = json?.let { parseOffProduct(it) } ?: return ScanResult.NotFound
        return ScanResult.Found(draft.copy(barcode = code))
    }

    suspend fun mealByBarcode(code: String): MealEntity? = meals.byBarcode(code)

    /** Logs a saved meal with exact numbers: no AI call. */
    suspend fun logMeal(date: LocalDate, mealId: Long, servings: Double = 1.0) = db.withTransaction {
        val meal = meals.get(mealId) ?: return@withTransaction
        val t = now()
        db.dayDao().insertIgnore(DayEntity(date.key()))
        val id = food.insert(
            FoodEntryEntity(
                date = date.key(),
                time = if (date == t.toLocalDate()) t.hhmm() else "12:00",
                text = meal.name,
                loggedAt = System.currentTimeMillis(),
            )
        )
        applyMeal(id, meal, servings)
    }

    /**
     * Changes how many servings a meal entry was. Scales from the saved meal when it still exists,
     * otherwise from the entry's own numbers.
     */
    suspend fun setServings(entryId: Long, servings: Double) = db.withTransaction {
        val entry = food.get(entryId) ?: return@withTransaction
        if (!entry.isMeal || servings <= 0) return@withTransaction
        val meal = entry.mealId?.let { meals.get(it) }
        if (meal != null) {
            applyMeal(entryId, meal, servings, text = entry.text)
        } else {
            val k = servings / entry.servings
            fun s(x: Int?) = ((x ?: 0) * k).roundToInt()
            food.setMeal(entryId, null, servings, entry.text, s(entry.kcalLow), s(entry.proteinG), s(entry.carbsG), s(entry.fatG), System.currentTimeMillis())
        }
    }

    /**
     * Turns an entry into a saved meal (typically an AI estimate you've corrected from the label),
     * and links the entry to it so it shows the exact numbers.
     */
    suspend fun saveEntryAsMeal(entryId: Long, meal: MealEntity): Result = db.withTransaction {
        when (val v = validate(meal.copy(id = 0))) {
            is Validated.Bad -> v.problem
            is Validated.Good -> {
                val id = meals.insert(v.meal)
                applyMeal(entryId, v.meal.copy(id = id), 1.0)
                Result.Ok
            }
        }
    }

    private suspend fun applyMeal(entryId: Long, meal: MealEntity, servings: Double, text: String = meal.name) {
        fun s(x: Int) = (x * servings).roundToInt()
        food.setMeal(entryId, meal.id, servings, text, s(meal.kcal), s(meal.proteinG), s(meal.carbsG), s(meal.fatG), System.currentTimeMillis())
    }

    // ---- Exercises ----

    fun observeCatalog(): Flow<List<CatalogExercise>> = exercises.observeCatalog()

    suspend fun addExercise(name: String): Result {
        val n = name.trim()
        if (n.isEmpty()) return Result.Problem("Give the exercise a name.")
        if (exercises.idFor(n) != null) return Result.Problem("\"$n\" is already in your library.")
        exercises.insertIgnore(ExerciseEntity(name = n))
        return Result.Ok
    }

    /** History follows the rename: workouts point at the exercise, not its spelling. */
    suspend fun renameExercise(id: Long, name: String): Result {
        val n = name.trim()
        if (n.isEmpty()) return Result.Problem("Give the exercise a name.")
        if (exercises.nameTakenByOther(n, id) > 0) return Result.Problem("\"$n\" is already in your library.")
        exercises.rename(id, n)
        return Result.Ok
    }

    suspend fun setExerciseHidden(id: Long, hidden: Boolean) = exercises.setHidden(id, hidden)

    /** False when the exercise has history or is in a routine workout; hide it instead. */
    suspend fun deleteExercise(id: Long): Boolean = exercises.deleteIfUnused(id) > 0
}
