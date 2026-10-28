package app.diurna.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NutritionTotalsTest {
    private fun entry(
        protein: Int? = 10, carbs: Int? = 20, fat: Int? = 5,
        fiber: Int? = null, sugars: Int? = null, sat: Int? = null,
        estimated: Boolean = true,
    ) = FoodEntryEntity(
        date = "2026-10-28", time = "12:00", text = "x", loggedAt = 1L,
        kcalLow = 100, kcalHigh = 100,
        proteinG = protein, carbsG = carbs, fatG = fat,
        fiberG = fiber, sugarsG = sugars, satFatG = sat,
        estimatedAt = if (estimated) 1L else null,
    )

    @Test fun unknownDetailStaysNullInTotals() {
        val t = listOf(entry(), entry(fiber = null, sugars = null)).totals()
        assertNull(t.fiberG)
        assertNull(t.sugarsG)
        assertEquals(20, t.proteinG)
        assertEquals(40, t.carbsG)
    }

    @Test fun knownZeroSumsAsZero() {
        val t = listOf(entry(fiber = 0), entry(fiber = 0)).totals()
        assertEquals(0, t.fiberG)
    }

    @Test fun mixesKnownAndUnknown() {
        val t = listOf(entry(fiber = 5), entry(fiber = null)).totals()
        assertEquals(5, t.fiberG)
    }
}
