package app.diurna.data

import androidx.room.withTransaction
import app.diurna.domain.AminoAcids
import app.diurna.domain.Cardio
import app.diurna.domain.RecipeMath
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
    private val ingredients = db.recipeIngredientDao()
    private val food = db.foodDao()
    private val exercises = db.exerciseDao()

    sealed interface Result {
        data object Ok : Result
        data class Problem(val message: String) : Result
    }

    // ---- Meals ----

    fun observeMeals(): Flow<List<MealEntity>> = meals.observeByUse()

    /** [kind] null = all; [MealEntity.KIND_SIMPLE] / [MealEntity.KIND_RECIPE]. */
    fun observeMeals(kind: String?): Flow<List<MealEntity>> =
        if (kind == null) meals.observeByUse() else meals.observeByUseFiltered(kind)

    fun observeIngredients(mealId: Long): Flow<List<RecipeIngredientEntity>> =
        ingredients.observeForMeal(mealId)

    suspend fun ingredientsFor(mealId: Long): List<RecipeIngredientEntity> =
        ingredients.forMeal(mealId)

    /** Adds a meal, or updates it when [meal] has an id. Past entries keep the numbers they were logged with. */
    suspend fun saveMeal(meal: MealEntity): Result = when (val v = validate(meal)) {
        is Validated.Bad -> v.problem
        is Validated.Good -> {
            if (meal.id == 0L) meals.insert(v.meal) else meals.update(v.meal)
            Result.Ok
        }
    }

    /**
     * Saves a recipe meal and replaces its ingredient snapshots. Recomputes per-serving totals
     * from [lines] via [RecipeMath]; refuses when a volume line has no resolved grams.
     */
    suspend fun saveRecipe(meal: MealEntity, lines: List<RecipeIngredientEntity>): Result = db.withTransaction {
        if (lines.isEmpty()) return@withTransaction Result.Problem("Add at least one ingredient.")
        val blocked = lines.firstOrNull { it.grams == null && IngredientNeedsMass(it) }
        if (blocked != null) {
            return@withTransaction Result.Problem(
                "\"${blocked.name}\" needs a density (or grams) before volume can convert to mass.",
            )
        }
        val yield = meal.servingsYield?.takeIf { it > 0 } ?: 1.0
        val totals = RecipeMath.perServing(lines.map { it.toNutrition() }, yield)
        val withTotals = meal.copy(
            kind = MealEntity.KIND_RECIPE,
            servingsYield = yield,
            kcal = totals.kcal,
            proteinG = totals.proteinG,
            carbsG = totals.carbsG,
            fatG = totals.fatG,
            fiberG = totals.fiberG,
            solubleFiberG = totals.solubleFiberG,
            insolubleFiberG = totals.insolubleFiberG,
            sugarsG = totals.sugarsG,
            addedSugarsG = totals.addedSugarsG,
            starchG = totals.starchG,
            satFatG = totals.satFatG,
            monoFatG = totals.monoFatG,
            polyFatG = totals.polyFatG,
            transFatG = totals.transFatG,
            cholesterolMg = totals.cholesterolMg,
            omega3G = totals.omega3G,
            omega6G = totals.omega6G,
            omega9G = totals.omega9G,
            hisMg = totals.aminos.hisMg,
            ileMg = totals.aminos.ileMg,
            leuMg = totals.aminos.leuMg,
            lysMg = totals.aminos.lysMg,
            metMg = totals.aminos.metMg,
            pheMg = totals.aminos.pheMg,
            thrMg = totals.aminos.thrMg,
            trpMg = totals.aminos.trpMg,
            valMg = totals.aminos.valMg,
            cysMg = totals.aminos.cysMg,
            tyrMg = totals.aminos.tyrMg,
            aminoMatched = totals.aminoMatched,
            aminoTotal = totals.aminoTotal,
        )
        when (val v = validate(withTotals)) {
            is Validated.Bad -> v.problem
            is Validated.Good -> {
                val id = if (v.meal.id == 0L) meals.insert(v.meal) else {
                    meals.update(v.meal)
                    v.meal.id
                }
                ingredients.deleteForMeal(id)
                ingredients.insertAll(lines.mapIndexed { i, line -> line.copy(id = 0, mealId = id, position = i) })
                Result.Ok
            }
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
        val kind = when (meal.kind) {
            MealEntity.KIND_RECIPE, MealEntity.KIND_RESTAURANT -> meal.kind
            else -> MealEntity.KIND_SIMPLE
        }
        return Validated.Good(
            meal.copy(
                name = name,
                note = meal.note?.trim()?.ifEmpty { null },
                kind = kind,
                servingsYield = meal.servingsYield?.takeIf { it > 0 },
                cookedWeightG = meal.cookedWeightG?.takeIf { it > 0 },
            ),
        )
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
        return ScanResult.Found(draft.copy(barcode = code, kind = MealEntity.KIND_SIMPLE))
    }

    suspend fun mealByBarcode(code: String): MealEntity? = meals.byBarcode(code)

    suspend fun getMeal(id: Long): MealEntity? = meals.get(id)

    /** Logs a saved meal with exact numbers: no AI call. */
    suspend fun logMeal(date: LocalDate, mealId: Long, servings: Double = 1.0, photo: String? = null) = db.withTransaction {
        val meal = meals.get(mealId) ?: return@withTransaction
        val t = now()
        db.dayDao().insertIgnore(DayEntity(date.key()))
        val id = food.insert(
            FoodEntryEntity(
                date = date.key(),
                time = if (date == t.toLocalDate()) t.hhmm() else "12:00",
                text = meal.name,
                loggedAt = System.currentTimeMillis(),
                photo = photo,
            )
        )
        applyMeal(id, meal, servings)
    }

    /**
     * Logs a recipe by cooked grams eaten when [MealEntity.cookedWeightG] is set.
     * servings = gramsEaten / cookedWeightG.
     */
    suspend fun logMealByGrams(date: LocalDate, mealId: Long, gramsEaten: Double, photo: String? = null): Result {
        val meal = meals.get(mealId) ?: return Result.Problem("Meal not found.")
        val cooked = meal.cookedWeightG ?: return Result.Problem("This recipe has no cooked weight.")
        if (gramsEaten <= 0 || cooked <= 0) return Result.Problem("Grams must be positive.")
        logMeal(date, mealId, servings = gramsEaten / cooked, photo = photo)
        return Result.Ok
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
            fun o(x: Int?) = x?.let { (it * k).roundToInt() }
            food.setMeal(
                entryId, null, servings, entry.text, s(entry.kcalLow), s(entry.proteinG), s(entry.carbsG), s(entry.fatG),
                o(entry.fiberG), o(entry.solubleFiberG), o(entry.insolubleFiberG),
                o(entry.sugarsG), o(entry.addedSugarsG), o(entry.starchG),
                o(entry.satFatG), o(entry.monoFatG), o(entry.polyFatG), o(entry.transFatG),
                o(entry.cholesterolMg), o(entry.omega3G), o(entry.omega6G), o(entry.omega9G),
                o(entry.hisMg), o(entry.ileMg), o(entry.leuMg), o(entry.lysMg), o(entry.metMg),
                o(entry.pheMg), o(entry.thrMg), o(entry.trpMg), o(entry.valMg), o(entry.cysMg), o(entry.tyrMg),
                entry.ingredientsJson, entry.aminoMatched, entry.aminoTotal,
                System.currentTimeMillis(),
            )
        }
    }

    /**
     * Turns an entry into a saved meal (typically an AI estimate you've corrected from the label),
     * and links the entry to it so it shows the exact numbers.
     */
    suspend fun saveEntryAsMeal(entryId: Long, meal: MealEntity): Result = db.withTransaction {
        when (val v = validate(meal.copy(id = 0, kind = MealEntity.KIND_SIMPLE))) {
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
        fun o(x: Int?) = x?.let { (it * servings).roundToInt() }
        food.setMeal(
            entryId, meal.id, servings, text, s(meal.kcal), s(meal.proteinG), s(meal.carbsG), s(meal.fatG),
            o(meal.fiberG), o(meal.solubleFiberG), o(meal.insolubleFiberG),
            o(meal.sugarsG), o(meal.addedSugarsG), o(meal.starchG),
            o(meal.satFatG), o(meal.monoFatG), o(meal.polyFatG), o(meal.transFatG),
            o(meal.cholesterolMg), o(meal.omega3G), o(meal.omega6G), o(meal.omega9G),
            o(meal.hisMg), o(meal.ileMg), o(meal.leuMg), o(meal.lysMg), o(meal.metMg),
            o(meal.pheMg), o(meal.thrMg), o(meal.trpMg), o(meal.valMg), o(meal.cysMg), o(meal.tyrMg),
            meal.ingredientsJson, meal.aminoMatched, meal.aminoTotal,
            System.currentTimeMillis(),
        )
    }

    // ---- Exercises ----

    fun observeCatalog(): Flow<List<CatalogExercise>> = exercises.observeCatalog()

    /** [cardio]: logged in that style (distance, time, pace…) instead of weight × reps; null for strength. */
    suspend fun addExercise(name: String, cardio: Cardio.Style? = null): Result {
        val n = name.trim()
        if (n.isEmpty()) return Result.Problem("Give the exercise a name.")
        if (exercises.idFor(n) != null) return Result.Problem("\"$n\" is already in your library.")
        exercises.insertIgnore(
            if (cardio == null) ExerciseEntity(name = n)
            else ExerciseEntity(name = n, kind = ExerciseEntity.KIND_CARDIO, cardioStyle = cardio.key)
        )
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

    /**
     * Strength (null) or cardio in a [Cardio.Style]. Switching between strength and cardio is
     * refused once the exercise is logged, so its history never mixes the two; a cardio exercise's
     * style can change any time, since it only changes how pieces are typed and shown.
     */
    suspend fun setExerciseCardio(id: Long, cardio: Cardio.Style?): Result {
        val e = exercises.get(id) ?: return Result.Ok
        if (e.isCardio && cardio != null) {
            exercises.setCardioStyle(id, cardio.key)
            return Result.Ok
        }
        if (!e.isCardio && cardio == null) return Result.Ok
        val changed = exercises.setKindIfUnlogged(
            id, if (cardio != null) ExerciseEntity.KIND_CARDIO else ExerciseEntity.KIND_STRENGTH, cardio?.key,
        )
        return if (changed > 0) Result.Ok else Result.Problem("Logged exercises keep their kind. Add a new exercise instead.")
    }

    /** False when the exercise has history or is in a routine workout; hide it instead. */
    suspend fun deleteExercise(id: Long): Boolean = exercises.deleteIfUnused(id) > 0
}

/** Volume / each without resolved grams needs a density or grams-per-each. */
private fun IngredientNeedsMass(line: RecipeIngredientEntity): Boolean {
    val u = line.unit.lowercase()
    return u == "ml" || u == "cup" || u == "tbsp" || u == "tsp" || u == "each"
}

fun RecipeIngredientEntity.toNutrition(): RecipeMath.IngredientNutrition =
    RecipeMath.IngredientNutrition(
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        fiberG = fiberG,
        solubleFiberG = solubleFiberG,
        insolubleFiberG = insolubleFiberG,
        sugarsG = sugarsG,
        addedSugarsG = addedSugarsG,
        starchG = starchG,
        satFatG = satFatG,
        monoFatG = monoFatG,
        polyFatG = polyFatG,
        transFatG = transFatG,
        cholesterolMg = cholesterolMg,
        omega3G = omega3G,
        omega6G = omega6G,
        omega9G = omega9G,
        aminos = AminoAcids(
            hisMg, ileMg, leuMg, lysMg, metMg, pheMg, thrMg, trpMg, valMg, cysMg, tyrMg,
        ),
    )
