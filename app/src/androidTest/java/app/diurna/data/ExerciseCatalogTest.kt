package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExerciseCatalogTest {
    private val t = TestDb()

    @After fun tearDown() = t.close()

    private suspend fun names() = t.routine.observeExerciseNames().first()

    @Test fun seedingAddsEveryExerciseOnce() = runTest {
        seedExercises(t.db.openHelper.writableDatabase)
        seedExercises(t.db.openHelper.writableDatabase) // runs on every open; must stay idempotent
        assertEquals(SEED_EXERCISES.size, names().size)
    }

    @Test fun seedingKeepsYourSpellingInsteadOfDuplicating() = runTest {
        t.days.startBlankWorkout(t.mon)
        t.days.addExercise(t.mon, "bench press")
        seedExercises(t.db.openHelper.writableDatabase)
        val bench = names().filter { it.equals("bench press", ignoreCase = true) }
        assertEquals(listOf("bench press"), bench)
    }

    @Test fun mostLoggedExercisesComeFirst() = runTest {
        seedExercises(t.db.openHelper.writableDatabase)
        t.days.startBlankWorkout(t.mon)
        t.days.addExercise(t.mon, "Squat")
        t.days.addExercise(t.mon, "Squat")
        t.days.addExercise(t.mon, "Plank")
        assertEquals(listOf("Squat", "Plank"), names().take(2))
        assertTrue("unused seeds follow alphabetically", names().drop(2).let { it == it.sortedBy { n -> n.lowercase() } })
    }
}
