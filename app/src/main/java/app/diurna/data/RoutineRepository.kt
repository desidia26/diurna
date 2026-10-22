package app.diurna.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class Template(val id: Long, val name: String, val exercises: List<String>)

class RoutineRepository(
    private val db: AppDatabase,
    private val days: DayRepository,
    private val finalizer: DayFinalizer,
) {
    private val routine = db.routineDao()
    private val exercises = db.exerciseDao()

    fun observeRoutine(): Flow<List<RoutineDayEntity>> = routine.observeDays()

    fun observeExerciseNames(): Flow<List<String>> = exercises.observeNamesByUse()

    fun observeTemplates(): Flow<List<Template>> =
        combine(routine.observeTemplates(), routine.observeTemplateExercises()) { templates, rows ->
            templates.map { t -> Template(t.id, t.name, rows.filter { it.templateId == t.id }.map { it.name }) }
        }

    suspend fun setAlarm(weekday: Int, time: String) {
        // Pin finished and locked days first, so this edit only affects days still to come.
        finalizer.catchUp()
        days.freezeLockedDays()
        routine.setAlarm(weekday, time)
        days.onAlarmInputsChanged()
    }

    suspend fun setTemplate(weekday: Int, templateId: Long?) {
        finalizer.catchUp() // past days keep the workout they had planned
        routine.setTemplate(weekday, templateId)
    }

    /** The new workout's id, or null if the name is blank or already taken. */
    suspend fun addTemplate(name: String): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return routine.insertTemplate(WorkoutTemplateEntity(name = trimmed)).takeIf { it != -1L }
    }

    suspend fun deleteTemplate(id: Long) {
        finalizer.catchUp()
        routine.deleteTemplate(id)
    }

    suspend fun setTemplateExercises(templateId: Long, names: List<String>) = db.withTransaction {
        routine.clearTemplateExercises(templateId)
        val rows = names.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.mapIndexed { i, name ->
            TemplateExerciseEntity(templateId, i, exercises.getOrCreate(name))
        }
        routine.insertTemplateExercises(rows)
    }
}
