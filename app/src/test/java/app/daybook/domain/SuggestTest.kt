package app.daybook.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestTest {
    // Most-used first, as the database returns them.
    private val names = listOf("Squat", "Bench press", "Incline bench press", "Front squat", "Barbell row", "Dumbbell bench press")

    @Test fun prefixMatchesComeFirstThenContains() {
        assertEquals(listOf("Bench press", "Incline bench press", "Dumbbell bench press"), suggest("bench", names))
        assertEquals(listOf("Squat", "Front squat"), suggest("squ", names))
    }

    @Test fun usageOrderIsKeptWithinAGroup() {
        // Both start with "b"; Bench press is used more, so it leads.
        assertEquals(listOf("Bench press", "Barbell row"), suggest("b", names).take(2))
    }

    @Test fun ignoresCaseAndSurroundingSpaces() {
        assertEquals(listOf("Barbell row"), suggest("  ROW ", names))
    }

    @Test fun nothingForBlankOrExactMatch() {
        assertTrue(suggest("  ", names).isEmpty())
        assertTrue(suggest("squat", names) == listOf("Front squat"))
    }

    @Test fun respectsLimit() {
        assertEquals(2, suggest("e", names, limit = 2).size)
    }
}
