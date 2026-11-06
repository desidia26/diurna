package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Daily micronutrient totals from per-entry profiles (already scaled to what was eaten).
 * Completeness is items that contributed any micro vs items considered for the day.
 */
class DailyMicrosTest {

    @Test fun sumsKnownAcrossEntriesAndTracksCompleteness() {
        val e1 = MicronutrientProfile.of(NutrientId.SODIUM to 100.0, NutrientId.IRON to 1.0)
        val e2 = MicronutrientProfile.of(NutrientId.SODIUM to 50.0)
        val e3 = MicronutrientProfile() // logged food with no micros
        val day = DailyMicros.forEntries(listOf(e1, e2, e3))
        assertEquals(150.0, day.profile[NutrientId.SODIUM]!!, 0.0)
        assertEquals(1.0, day.profile[NutrientId.IRON]!!, 0.0)
        assertNull(day.profile[NutrientId.CALCIUM])
        assertEquals(2, day.contributing)
        assertEquals(3, day.total)
        assertTrue(day.partial)
        assertEquals("from 2 of 3 items", day.completenessLabel)
    }

    @Test fun emptyDayHasNoCard() {
        assertNull(DailyMicros.forEntries(emptyList()))
        assertNull(DailyMicros.forEntries(listOf(MicronutrientProfile(), MicronutrientProfile())))
    }

    @Test fun allContributingNotPartial() {
        val day = DailyMicros.forEntries(
            listOf(
                MicronutrientProfile.of(NutrientId.VITAMIN_C to 10.0),
                MicronutrientProfile.of(NutrientId.VITAMIN_C to 5.0),
            ),
        )!!
        assertEquals(15.0, day.profile[NutrientId.VITAMIN_C]!!, 0.0)
        assertEquals(false, day.partial)
        assertEquals("from 2 of 2 items", day.completenessLabel)
    }
}
