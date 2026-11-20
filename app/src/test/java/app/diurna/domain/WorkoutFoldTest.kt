package app.diurna.domain

import app.diurna.data.ExerciseSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutFoldTest {
    private fun set(reps: Int? = null, distanceM: Int? = null, durationS: Double? = null) =
        ExerciseSetEntity(loggedExerciseId = 1, position = 0, weight = 100.0.takeIf { reps != null }, reps = reps, distanceM = distanceM, durationS = durationS)

    @Test fun everySetDoneIsDone() {
        assertTrue(WorkoutFold.allDone(listOf(listOf(set(5), set(5)), listOf(set(8)))))
    }

    @Test fun oneBlankSetKeepsItOpen() {
        assertFalse(WorkoutFold.allDone(listOf(listOf(set(5), set()), listOf(set(8)))))
    }

    @Test fun cardioCountsOnceADistanceOrTimeIsIn() {
        assertTrue(WorkoutFold.allDone(listOf(listOf(set(distanceM = 2000)), listOf(set(durationS = 600.0)))))
        assertFalse(WorkoutFold.allDone(listOf(listOf(set(5)), listOf(set()))))
    }

    @Test fun nothingToDoIsNotDone() {
        assertFalse(WorkoutFold.allDone(emptyList()))
        assertFalse(WorkoutFold.allDone(listOf(emptyList())))
        assertFalse(WorkoutFold.allDone(listOf(listOf(set(5)), emptyList())))
    }

    @Test fun arrivingFoldsADoneWorkoutAndOpensAnUnfinishedOne() {
        val done = listOf(listOf(set(5)))
        val open = listOf(listOf(set()))
        assertEquals(setOf("food", "workout"), WorkoutFold.onArrival(setOf("food"), done))
        assertEquals(setOf("food"), WorkoutFold.onArrival(setOf("food", "workout"), open))
    }

    @Test fun arrivingLeavesOtherFoldsAlone() {
        val folded = setOf("morning", "exercise:7")
        assertEquals(folded + "workout", WorkoutFold.onArrival(folded, listOf(listOf(set(5)))))
        assertEquals(folded, WorkoutFold.onArrival(folded, emptyList()))
    }
}
