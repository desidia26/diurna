package app.diurna.ui

import app.diurna.AppContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineViewModel(private val c: AppContainer) : ScreenViewModel() {
    val routineDays = c.routine.observeRoutine().state(emptyList())
    val templateList = c.routine.observeTemplates().state(emptyList())
    val exerciseNames = c.settings.cardio.flatMapLatest { c.routine.observeExerciseNames(it) }.state(emptyList())

    fun setRoutineAlarm(weekday: Int, time: String) = io { c.routine.setAlarm(weekday, time) }
    fun setRoutineTemplate(weekday: Int, templateId: Long?) = io { c.routine.setTemplate(weekday, templateId) }
    /** Workouts opened up on the Routine screen; all folded by default. Kept while the app is open. */
    val expanded = MutableStateFlow(emptySet<Long>())
    fun toggleExpanded(id: Long) {
        expanded.value = expanded.value.let { if (id in it) it - id else it + id }
    }

    /** False if the name is blank or taken; a new workout opens so you can add exercises. */
    suspend fun addTemplate(name: String): Boolean {
        val id = c.routine.addTemplate(name) ?: return false
        expanded.value = expanded.value + id
        return true
    }
    fun deleteTemplate(id: Long) = io { c.routine.deleteTemplate(id) }
    fun setTemplateExercises(id: Long, names: List<String>) = io { c.routine.setTemplateExercises(id, names) }
}
