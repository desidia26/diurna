package app.diurna.domain

import app.diurna.data.ExerciseSetEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SetHintsTest {
    private fun set(weight: Double?, reps: Int?) = ExerciseSetEntity(loggedExerciseId = 1, position = 0, weight = weight, reps = reps)
    private val last = listOf(set(135.0, 10), set(145.0, 8), set(155.0, 6))

    @Test fun weightFollowsTheSetAboveThisSession() {
        val session = listOf(set(140.0, 10), set(null, null))
        assertEquals(SetHints.Hint(140.0, 8), SetHints.hint(1, session, last))
    }

    @Test fun emptySetAboveFallsBackToLastTime() {
        val session = listOf(set(null, null), set(null, null))
        assertEquals(SetHints.Hint(145.0, 8), SetHints.hint(1, session, last))
        assertEquals("first set has nothing above", SetHints.Hint(135.0, 10), SetHints.hint(0, session, last))
    }

    @Test fun moreSetsThanLastTimeUseLastTimesFinalSet() {
        val session = List(5) { set(null, null) }
        assertEquals(SetHints.Hint(155.0, 6), SetHints.hint(4, session, last))
    }

    @Test fun neverDoneUsesTheSetAbove() {
        val session = listOf(set(50.0, 12), set(null, null))
        assertEquals(SetHints.Hint(50.0, 12), SetHints.hint(1, session, emptyList()))
        assertEquals(SetHints.Hint(null, null), SetHints.hint(0, session, emptyList()))
    }
}
