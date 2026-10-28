package app.diurna.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt

/** Mirrors LibraryRepository.applyMeal scaling: required × servings; optional stays null when unknown. */
class MealScaleTest {
    private fun scaleInt(v: Int, s: Double) = (v * s).roundToInt()
    private fun scaleOpt(v: Int?, s: Double) = v?.let { (it * s).roundToInt() }

    @Test fun scalesRequiredAndOptional() {
        val s = 2.0
        assertEquals(200, scaleInt(100, s))
        assertEquals(10, scaleOpt(5, s))
        assertNull(scaleOpt(null, s))
    }

    @Test fun halfServingRounds() {
        assertEquals(3, scaleInt(5, 0.5))
        assertEquals(1, scaleOpt(1, 0.5))
        assertEquals(2, scaleOpt(3, 0.5))
    }

    @Test fun zeroOptionalStaysZeroNotNull() {
        assertEquals(0, scaleOpt(0, 3.0))
    }
}
