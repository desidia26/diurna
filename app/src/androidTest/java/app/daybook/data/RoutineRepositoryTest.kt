package app.daybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoutineRepositoryTest {
    private val t = TestDb()
    private val routine = t.routine

    @After fun tearDown() = t.close()

    @Test fun newDatabaseHasEveryWeekdayAtDefaultAlarm() = runTest {
        val days = routine.observeRoutine().first()
        assertEquals((1..7).toList(), days.map { it.weekday })
        assertTrue(days.all { it.alarmTime == AppDatabase.DEFAULT_ALARM && it.templateId == null })
    }

    @Test fun templateNamesMustBeNonBlankAndUnique() = runTest {
        assertNotNull(routine.addTemplate("Push A"))
        assertNull("duplicate", routine.addTemplate("Push A"))
        assertNull("blank", routine.addTemplate("   "))
        assertEquals(1, routine.observeTemplates().first().size)
    }

    @Test fun templateExercisesAreTrimmedDedupedAndOrdered() = runTest {
        val id = t.template("Push A", " Bench press", "", "Dips ", "bench press", "Overhead press")
        assertEquals(listOf("Bench press", "Dips", "Overhead press"), routine.observeTemplates().first().single { it.id == id }.exercises)
    }

    @Test fun editingTemplateDoesNotTouchLoggedWorkouts() = runTest {
        val id = t.template("Push A", "Bench press", "Dips")
        t.days.startWorkout(t.mon, id)
        routine.setTemplateExercises(id, listOf("Squat"))
        val logged = t.days.observeWorkout(t.mon).first()!!
        assertEquals(setOf("Bench press", "Dips"), logged.exercises.map { it.exercise.name }.toSet())
    }

    @Test fun deletingTemplateMakesItsWeekdaysRestDaysButKeepsHistory() = runTest {
        val id = t.template("Push A", "Bench press")
        routine.setTemplate(1, id)
        t.days.startWorkout(t.mon, id)
        routine.deleteTemplate(id)
        assertNull(routine.observeRoutine().first().first { it.weekday == 1 }.templateId)
        assertEquals("Push A", t.days.observeWorkout(t.mon).first()?.workout?.name)
    }
}
