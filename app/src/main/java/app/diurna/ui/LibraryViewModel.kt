package app.diurna.ui

import android.net.Uri
import app.diurna.AppContainer
import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.NoApiKey
import app.diurna.ai.FoodEstimate
import app.diurna.ai.NutritionLabelReading
import app.diurna.data.LibraryRepository.Result
import app.diurna.data.MealEntity
import app.diurna.data.RecipeIngredientEntity
import app.diurna.data.fdc.FdcNutritionProfile
import app.diurna.data.fdc.FdcSearchHit
import app.diurna.domain.Cardio
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
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
class LibraryViewModel(private val c: AppContainer) : ScreenViewModel(), PhotoImportHost {
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

    fun setCardio(on: Boolean) = io { c.settings.setCardio(on) }
    fun setDetailedNutrition(on: Boolean) = io { c.settings.setDetailedNutrition(on) }

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
    suspend fun scanBarcode(raw: String, upcE: Boolean = false) = c.library.scan(raw, upcE)

    suspend fun addExercise(name: String, cardio: Cardio.Style? = null): Result = c.library.addExercise(name, cardio)
    suspend fun setExerciseCardio(id: Long, cardio: Cardio.Style?): Result = c.library.setExerciseCardio(id, cardio)
    suspend fun renameExercise(id: Long, name: String): Result = c.library.renameExercise(id, name)

    fun deleteMeal(id: Long) = io { c.library.deleteMeal(id) }
    fun setExerciseHidden(id: Long, hidden: Boolean) = io { c.library.setExerciseHidden(id, hidden) }
    fun deleteExercise(id: Long) = io { c.library.deleteExercise(id) }

    override val captureFile get() = c.photoImporter.captureFile
    override suspend fun importPhoto(uri: Uri) = c.photoImporter.import(uri)
    override suspend fun importCapture() = c.photoImporter.importCapture()
    fun discardPhoto(name: String?) = io {
        withContext(Dispatchers.IO) { c.foodPhotos.delete(name) }
    }

    suspend fun readLabelPhoto(name: String): LabelPhotoResult = withContext(Dispatchers.IO) {
        val bytes = c.foodPhotos.read(name)
        // Always drop the temp file: the label photo is only for filling fields, not stored on the meal.
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
        } catch (e: IOException) {
            LabelPhotoResult.Problem("Couldn't reach the AI. Check your connection and try again.")
        } catch (e: Exception) {
            LabelPhotoResult.Problem("Couldn't read the label (${e.javaClass.simpleName})")
        }
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
        val bytes = c.foodPhotos.read(name)
        c.foodPhotos.delete(name)
        if (bytes == null) return@withContext RestaurantEstimateResult.Problem("Couldn't read that photo")
        val text = buildString {
            append("Restaurant meal")
            if (restaurantHint.isNotBlank()) append(" from ").append(restaurantHint.trim())
            if (dishHint.isNotBlank()) append(": ").append(dishHint.trim())
            else append(" (see photo of menu or nutrition page)")
        }
        try {
            RestaurantEstimateResult.Ok(c.foodEstimator.estimate(text, bytes, detailed = detailed, ingredients = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoApiKey) {
            RestaurantEstimateResult.Problem(e.message ?: "Add an API key in Setup to estimate from photos")
        } catch (e: EstimateFailed) {
            RestaurantEstimateResult.Problem(e.message ?: "Couldn't estimate from that photo")
        } catch (e: EstimateRetryable) {
            RestaurantEstimateResult.Problem("Couldn't reach the AI. Check your connection and try again.")
        } catch (e: IOException) {
            RestaurantEstimateResult.Problem("Couldn't reach the AI. Check your connection and try again.")
        } catch (e: Exception) {
            RestaurantEstimateResult.Problem("Couldn't estimate (${e.javaClass.simpleName})")
        }
    }
}
