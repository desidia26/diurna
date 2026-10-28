package app.diurna.ui

import app.diurna.AppContainer
import app.diurna.data.LibraryRepository.Result
import app.diurna.data.MealEntity
import app.diurna.data.RecipeIngredientEntity
import app.diurna.data.fdc.FdcNutritionProfile
import app.diurna.data.fdc.FdcSearchHit
import app.diurna.domain.Cardio
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val c: AppContainer) : ScreenViewModel() {
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
}
