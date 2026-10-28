package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MetricsMacroTest {
    private val oct1: LocalDate = LocalDate.of(2026, 10, 1)
    private fun d(day: Int): LocalDate = oct1.plusDays(day - 1L)

    private fun stack(day: Int, p: Int, c: Int, f: Int) =
        Metrics.macroKcal(d(day), p, c, f)

    @Test fun gramsToKcalUsesFourFourNine() {
        val s = Metrics.macroKcal(d(1), proteinG = 30, carbsG = 40, fatG = 10)
        assertEquals(120.0, s.proteinKcal, 1e-9) // 30×4
        assertEquals(160.0, s.carbsKcal, 1e-9)   // 40×4
        assertEquals(90.0, s.fatKcal, 1e-9)      // 10×9
        assertEquals(370.0, s.total, 1e-9)
    }

    @Test fun missingGramsCountAsZero() {
        val s = Metrics.macroKcal(d(1), proteinG = null, carbsG = 10, fatG = null)
        assertEquals(0.0, s.proteinKcal, 1e-9)
        assertEquals(40.0, s.carbsKcal, 1e-9)
        assertEquals(0.0, s.fatKcal, 1e-9)
        assertEquals(40.0, s.total, 1e-9)
    }

    @Test fun dailyStacksLeaveGapsForDaysWithoutFood() {
        val byDate = mapOf(d(1) to stack(1, 25, 50, 10), d(3) to stack(3, 0, 0, 0))
        val stacks = Metrics.dailyMacroStacks(byDate, d(1), d(3))
        assertEquals(3, stacks.size)
        assertEquals(25 * 4.0, stacks[0]!!.proteinKcal, 1e-9)
        assertNull("Oct 2 had no food", stacks[1])
        assertEquals(0.0, stacks[2]!!.total, 1e-9) // logged but all-zero still a bar
    }

    @Test fun weeklyAveragesOnlyFoodDaysAndGapEmptyWeeks() {
        // Week of Sep 28 (Mon) … Oct 4; Oct 5 is next Monday.
        val byDate = mapOf(
            d(1) to stack(1, 25, 25, 10), // Thu: 100+100+90 = 290
            d(2) to stack(2, 50, 0, 0),   // Fri: 200
            d(8) to stack(8, 10, 10, 10), // next week Thu
        )
        val weeks = Metrics.weeklyMacroAverages(byDate, d(1), d(8))
        assertEquals(2, weeks.size)
        // First week: avg of Thu+Fri → protein (100+200)/2 = 150, carbs 50, fat 45
        assertEquals(150.0, weeks[0]!!.proteinKcal, 1e-9)
        assertEquals(50.0, weeks[0]!!.carbsKcal, 1e-9)
        assertEquals(45.0, weeks[0]!!.fatKcal, 1e-9)
        assertEquals(d(8).with(java.time.DayOfWeek.MONDAY), weeks[1]!!.date)
        assertEquals(Metrics.macroKcal(d(8), 10, 10, 10).total, weeks[1]!!.total, 1e-9)
    }

    @Test fun weeklyAverageIsNullWhenWeekHasNoFood() {
        val byDate = mapOf(d(8) to stack(8, 10, 0, 0)) // only second week
        val weeks = Metrics.weeklyMacroAverages(byDate, d(1), d(8))
        assertNull(weeks[0])
        assertEquals(40.0, weeks[1]!!.total, 1e-9)
    }

    @Test fun percentSplitSumsToOneHundred() {
        val s = stack(1, 25, 50, 10) // 100 + 200 + 90 = 390
        val p = Metrics.macroPercent(s)!!
        assertEquals(100.0 * 100 / 390, p.proteinKcal, 1e-9)
        assertEquals(100.0 * 200 / 390, p.carbsKcal, 1e-9)
        assertEquals(100.0 * 90 / 390, p.fatKcal, 1e-9)
        assertEquals(100.0, p.total, 1e-9)
    }

    @Test fun percentOfZeroTotalIsGapNotDivideByZero() {
        assertNull(Metrics.macroPercent(stack(1, 0, 0, 0)))
        assertNull(Metrics.macroPercent(null))
    }

    @Test fun averageSkipsGaps() {
        val stacks = listOf(stack(1, 25, 0, 0), null, stack(3, 50, 0, 0))
        assertEquals(150.0, Metrics.averageMacroTotal(stacks)!!, 1e-9) // (100+200)/2
        val avg = Metrics.averageMacroStack(stacks)!!
        assertEquals(150.0, avg.proteinKcal, 1e-9)
        assertEquals(0.0, avg.carbsKcal, 1e-9)
        assertNull(Metrics.averageMacroTotal(listOf(null, null)))
        assertNull(Metrics.averageMacroStack(emptyList()))
    }

    @Test fun percentModeTurnsZeroDaysIntoGapsInASeries() {
        val byDate = mapOf(d(1) to stack(1, 10, 0, 0), d(2) to stack(2, 0, 0, 0))
        val stacks = Metrics.dailyMacroStacks(byDate, d(1), d(2))
        val pct = stacks.map(Metrics::macroPercent)
        assertTrue(pct[0] != null)
        assertNull("0 total → gap in % mode", pct[1])
    }
}
