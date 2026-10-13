package app.daybook.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import app.daybook.domain.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime

/** In-memory database plus repositories on an adjustable clock. Monday Oct 12 2026, noon by default. */
class TestDb {
    val mon: LocalDate = LocalDate.of(2026, 10, 12)
    var now: LocalDateTime = mon.atTime(12, 0)

    val db: AppDatabase = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
    ).addCallback(AppDatabase.seedRoutine).build()

    val days = DayRepository(db) { now }
    val profile = MutableStateFlow<Profile?>(null)
    val nutrition = NutritionRepository(db, profile)
    val finalizer = DayFinalizer(db, days, nutrition) { now }
    val routine = RoutineRepository(db, days, finalizer)
    val food = FoodRepository(db) { now }
    val library = LibraryRepository(db) { now }

    suspend fun template(name: String, vararg exercises: String): Long {
        routine.addTemplate(name)
        val id = db.routineDao().observeTemplates().first().first { it.name == name }.id
        routine.setTemplateExercises(id, exercises.toList())
        return id
    }

    /** Logs every set of [exercise] on [date] with the given reps at [weight]. */
    suspend fun fill(date: LocalDate, exercise: String, weight: Double, vararg reps: Int) {
        val ex = days.observeWorkout(date).first()!!.exercises.first { it.exercise.name == exercise }
        ex.sets.sortedBy { it.position }.forEachIndexed { i, s -> days.updateSet(s.id, weight, reps.getOrNull(i)) }
    }

    fun close() = db.close()
}
