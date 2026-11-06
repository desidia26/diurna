package app.diurna.ui

import android.net.Uri
import app.diurna.debug.operation
import app.diurna.AppContainer
import app.diurna.ai.FoodEstimate
import app.diurna.ai.NutritionLabelReading
import app.diurna.data.LibraryRepository.Result
import app.diurna.data.MealEntity
import app.diurna.data.RecipeIngredientEntity
import app.diurna.data.recipe.RecipeImportResult
import app.diurna.data.density.DensityLookupWorker
import app.diurna.data.density.needsDensityLookup
import app.diurna.domain.DensityUnitKind
import app.diurna.domain.IngredientUnit
import app.diurna.data.fdc.FdcNutritionProfile
import app.diurna.data.fdc.FdcSearchHit
import app.diurna.domain.Cardio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

sealed interface LabelPhotoResult {
    data class Read(val reading: NutritionLabelReading) : LabelPhotoResult
    data class Problem(val message: String) : LabelPhotoResult
}

sealed interface RestaurantEstimateResult {
    data class Ok(val estimate: FoodEstimate) : RestaurantEstimateResult
    data class Problem(val message: String) : RestaurantEstimateResult
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val c: AppContainer) : ScreenViewModel(c.actions), PhotoImportHost {
    /** null = All, [MealEntity.KIND_SIMPLE] = Meals, [MealEntity.KIND_RECIPE] = Recipes. */
    private val mealFilter = MutableStateFlow<String?>(null)
    val mealKindFilter = mealFilter

    val meals = mealFilter.flatMapLatest { kind -> c.library.observeMeals(kind) }.state(emptyList())
    val allMeals = c.library.observeMeals().state(emptyList())

    /** Setup's Cardio switch. Off: Library looks as it did before cardio existed. */
    val cardio = c.settings.cardio.state(false)
    /** Setup's Detailed nutrition switch. Off: meal form and Day food stay basic P/C/F. */
    val detailedNutrition = c.settings.detailedNutrition.state(false)

    val hasFdcKey = c.usdaKey.lastFour.map { it != null }.state(false)

    fun setMealFilter(kind: String?) { mealFilter.value = kind }

    /** With cardio off, unlogged cardio (the seeded machines) is left out; anything with history stays. */
    val catalog = combine(c.library.observeCatalog(), c.settings.cardio) { all, on ->
        if (on) all else all.filter { !it.isCardio || it.logged > 0 }
    }.state(emptyList())

    fun setCardio(on: Boolean) = io("library.set_cardio") { c.settings.setCardio(on) }
    fun setDetailedNutrition(on: Boolean) = io("library.set_detailed_nutrition") { c.settings.setDetailedNutrition(on) }

    // Suspend so dialogs can show a problem (e.g. a duplicate name) instead of closing.
    suspend fun saveMeal(meal: MealEntity): Result =
        c.library.saveMeal(meal.copy(kind = if (meal.kind.isBlank()) MealEntity.KIND_SIMPLE else meal.kind))
    suspend fun saveMealWithPhotos(meal: MealEntity, photos: List<String>): Result =
        c.library.saveMealWithPhotos(
            meal.copy(kind = if (meal.kind.isBlank()) MealEntity.KIND_SIMPLE else meal.kind),
            photos,
        )
    fun mealPhotos(mealId: Long) = c.library.observeMealPhotos(mealId).map { rows -> rows.map { it.fileName } }
    val foodPhotos get() = c.foodPhotos
    suspend fun saveRecipe(meal: MealEntity, lines: List<RecipeIngredientEntity>): Result =
        c.library.saveRecipe(meal, lines)
    suspend fun loadIngredients(mealId: Long): List<RecipeIngredientEntity> =
        if (mealId == 0L) emptyList() else c.library.ingredientsFor(mealId)

    suspend fun searchFdc(query: String): List<FdcSearchHit> = c.fdcClient.search(query)
    suspend fun loadFdcNutrition(fdcId: Int): FdcNutritionProfile? = c.fdcClient.nutritionProfile(fdcId)
    suspend fun scanBarcode(raw: String, upcE: Boolean = false) = c.debug.operation("barcode.lookup", app.diurna.debug.Screen.LIBRARY) {
        c.debug.registerAttachment(raw.toByteArray(), app.diurna.debug.AttachmentKind.BARCODE)
        c.library.scan(raw, upcE)
    }

    suspend fun addExercise(name: String, cardio: Cardio.Style? = null): Result = c.library.addExercise(name, cardio)
    suspend fun setExerciseCardio(id: Long, cardio: Cardio.Style?): Result = c.library.setExerciseCardio(id, cardio)
    suspend fun renameExercise(id: Long, name: String): Result = c.library.renameExercise(id, name)
    suspend fun setExerciseNote(id: Long, note: String) = c.library.setExerciseNote(id, note)

    fun deleteMeal(id: Long) = io("library.delete_meal") { c.library.deleteMeal(id) }
    fun setExerciseHidden(id: Long, hidden: Boolean) = io("library.set_exercise_hidden") { c.library.setExerciseHidden(id, hidden) }
    fun deleteExercise(id: Long) = io("library.delete_exercise") { c.library.deleteExercise(id) }

    override val captureFile get() = c.photoImporter.captureFile
    override suspend fun importPhoto(uri: Uri) = c.photoImporter.import(uri)
    override suspend fun importCapture() = c.photoImporter.importCapture()
    fun discardPhoto(name: String?) = io("library.discard_photo") {
        withContext(Dispatchers.IO) { c.foodPhotos.delete(name) }
    }

    suspend fun readLabelPhoto(name: String): LabelPhotoResult = withContext(Dispatchers.IO) {
        // Always drop the temp file: the label photo is only for filling fields, not stored on the meal.
        PhotoAiReads.readLabelPhoto(name, c.foodPhotos, c.labelReader)
    }

    /**
     * Estimates a restaurant dish from a menu or nutrition-page photo via the selected food
     * estimator. Temp photo deleted after. Caller fills the form; Save confirms.
     */
    suspend fun estimateRestaurantPhoto(
        name: String,
        dishHint: String,
        restaurantHint: String,
        detailed: Boolean,
    ): RestaurantEstimateResult = withContext(Dispatchers.IO) {
        PhotoAiReads.estimateRestaurantPhoto(
            name, dishHint, restaurantHint, detailed, c.foodPhotos, c.foodEstimator,
        )
    }

    /**
     * Fetches a recipe page over hardened HTTPS and parses schema.org Recipe JSON-LD / microdata.
     * Does not call any AI provider and never attaches API keys.
     */
    suspend fun importRecipeFromUrl(url: String): RecipeImportResult = c.recipeImporter.importFromUrl(url)

    /**
     * Resolve density / grams-per-each for blocked volume/each lines. Uses the shared cache so
     * the same ingredient across recipes is looked up once. Manual density_id is never overwritten.
     */
    suspend fun resolveDensities(lines: List<RecipeIngredientEntity>): List<RecipeIngredientEntity> {
        val resolver = c.densityResolver
        return lines.map { line ->
            if (!line.needsDensityLookup()) return@map line
            val kind = DensityUnitKind.of(IngredientUnit.of(line.unit)) ?: return@map line
            val lookup = resolver.resolve(line.name, kind) ?: return@map line
            resolver.applyToLine(line, lookup)
        }
    }

    /** Kick a WorkManager pass for blocked lines on a saved recipe (or all recipes when id=0). */
    fun enqueueDensityLookup(mealId: Long = 0L) {
        DensityLookupWorker.enqueue(c.appContext, mealId)
    }
}
