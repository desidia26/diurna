package app.daybook.ui

import app.daybook.AppContainer

class RoutineViewModel(private val c: AppContainer) : ScreenViewModel() {
    val routineDays = c.routine.observeRoutine().state(emptyList())
    val templateList = c.routine.observeTemplates().state(emptyList())
    val exerciseNames = c.routine.observeExerciseNames().state(emptyList())

    fun setRoutineAlarm(weekday: Int, time: String) = io { c.routine.setAlarm(weekday, time) }
    fun setRoutineTemplate(weekday: Int, templateId: Long?) = io { c.routine.setTemplate(weekday, templateId) }
    fun addTemplate(name: String) = io { c.routine.addTemplate(name) }
    fun deleteTemplate(id: Long) = io { c.routine.deleteTemplate(id) }
    fun setTemplateExercises(id: Long, names: List<String>) = io { c.routine.setTemplateExercises(id, names) }
}
