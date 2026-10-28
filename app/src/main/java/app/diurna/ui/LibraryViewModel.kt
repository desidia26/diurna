package app.diurna.ui

import app.diurna.AppContainer
import app.diurna.data.LibraryRepository.Result
import app.diurna.data.MealEntity
import app.diurna.domain.Cardio
import kotlinx.coroutines.flow.combine

class LibraryViewModel(private val c: AppContainer) : ScreenViewModel() {
    val meals = c.library.observeMeals().state(emptyList())
    /** Setup's Cardio switch. Off: Library looks as it did before cardio existed. */
    val cardio = c.settings.cardio.state(false)
    /** Setup's Detailed nutrition switch. Off: meal form and Day food stay basic P/C/F. */
    val detailedNutrition = c.settings.detailedNutrition.state(false)

    /** With cardio off, unlogged cardio (the seeded machines) is left out; anything with history stays. */
    val catalog = combine(c.library.observeCatalog(), c.settings.cardio) { all, on ->
        if (on) all else all.filter { !it.isCardio || it.logged > 0 }
    }.state(emptyList())

    fun setCardio(on: Boolean) = io { c.settings.setCardio(on) }
    fun setDetailedNutrition(on: Boolean) = io { c.settings.setDetailedNutrition(on) }

    // Suspend so dialogs can show a problem (e.g. a duplicate name) instead of closing.
    suspend fun saveMeal(meal: MealEntity): Result = c.library.saveMeal(meal)
    suspend fun addExercise(name: String, cardio: Cardio.Style? = null): Result = c.library.addExercise(name, cardio)
    suspend fun setExerciseCardio(id: Long, cardio: Cardio.Style?): Result = c.library.setExerciseCardio(id, cardio)
    suspend fun renameExercise(id: Long, name: String): Result = c.library.renameExercise(id, name)

    fun deleteMeal(id: Long) = io { c.library.deleteMeal(id) }
    fun setExerciseHidden(id: Long, hidden: Boolean) = io { c.library.setExerciseHidden(id, hidden) }
    fun deleteExercise(id: Long) = io { c.library.deleteExercise(id) }
}
