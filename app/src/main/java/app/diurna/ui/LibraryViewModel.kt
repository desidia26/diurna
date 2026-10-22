package app.diurna.ui

import app.diurna.AppContainer
import app.diurna.data.LibraryRepository.Result
import app.diurna.data.MealEntity

class LibraryViewModel(private val c: AppContainer) : ScreenViewModel() {
    val meals = c.library.observeMeals().state(emptyList())
    val catalog = c.library.observeCatalog().state(emptyList())

    // Suspend so dialogs can show a problem (e.g. a duplicate name) instead of closing.
    suspend fun saveMeal(meal: MealEntity): Result = c.library.saveMeal(meal)
    suspend fun addExercise(name: String): Result = c.library.addExercise(name)
    suspend fun renameExercise(id: Long, name: String): Result = c.library.renameExercise(id, name)

    fun deleteMeal(id: Long) = io { c.library.deleteMeal(id) }
    fun setExerciseHidden(id: Long, hidden: Boolean) = io { c.library.setExerciseHidden(id, hidden) }
    fun deleteExercise(id: Long) = io { c.library.deleteExercise(id) }
}
