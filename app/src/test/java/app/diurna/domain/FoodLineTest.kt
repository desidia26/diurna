package app.diurna.domain

import app.diurna.domain.FoodLine.Kcal
import org.junit.Assert.assertEquals
import org.junit.Test

class FoodLineTest {
    @Test fun aSavedMealShowsItsExactNumber() {
        assertEquals(Kcal.Exact("320"), FoodLine.kcal(isMeal = true, low = 320, high = 320, estimated = false, failed = false))
        assertEquals(Kcal.Exact("1,240"), FoodLine.kcal(isMeal = true, low = 1240, high = 1240, estimated = false, failed = false))
    }

    @Test fun anEstimateShowsItsMidpointMarkedAsApproximate() {
        assertEquals(Kcal.Estimate("~695"), FoodLine.kcal(isMeal = false, low = 610, high = 780, estimated = true, failed = false))
    }

    @Test fun failedAndPendingEntriesHaveNoNumber() {
        assertEquals(Kcal.Failed, FoodLine.kcal(isMeal = false, low = null, high = null, estimated = false, failed = true))
        assertEquals(Kcal.Pending, FoodLine.kcal(isMeal = false, low = null, high = null, estimated = false, failed = false))
    }

    @Test fun kcalLeftReadsAsLeftOrOver() {
        assertEquals("870 left", FoodLine.kcalLeft(1330, 2200))
        assertEquals("on target", FoodLine.kcalLeft(2200, 2200))
        assertEquals("1,120 over", FoodLine.kcalLeft(3320, 2200))
    }
}
