package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseNoteTest {
    @Test fun blankClearsTheNote() {
        assertNull(ExerciseNote.clean(""))
        assertNull(ExerciseNote.clean("  \n \r\n  "))
    }

    @Test fun keepsLineBreaksButNotRunsOfBlankLines() {
        assertEquals("Seat 4\nPins at 7", ExerciseNote.clean("Seat 4  \r\nPins at 7\n"))
        assertEquals("Seat 4\n\nPause first rep", ExerciseNote.clean("\n\nSeat 4\n\n\n\nPause first rep\n\n"))
    }

    @Test fun keepsLeadingIndentOfLaterLines() {
        assertEquals("Cues:\n  elbows in", ExerciseNote.clean("Cues:\n  elbows in"))
    }

    @Test fun capsLinesAndLength() {
        assertEquals("1\n2\n3\n4\n5\n6", ExerciseNote.clean((1..9).joinToString("\n")))
        assertEquals(ExerciseNote.MAX_CHARS, ExerciseNote.clean("x".repeat(500))!!.length)
    }
}
