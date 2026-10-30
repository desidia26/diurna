package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MetricsSubMacroTest {
    private val oct1: LocalDate = LocalDate.of(2026, 10, 1)
    private fun d(day: Int): LocalDate = oct1.plusDays(day - 1L)

    private fun grams(
        day: Int,
        protein: Int = 0,
        carbs: Int = 0,
        fat: Int = 0,
        fiber: Int? = null,
        soluble: Int? = null,
        insoluble: Int? = null,
        sugars: Int? = null,
        added: Int? = null,
        starch: Int? = null,
        sat: Int? = null,
        mono: Int? = null,
        poly: Int? = null,
        trans: Int? = null,
        chol: Int? = null,
        o3: Int? = null,
        o6: Int? = null,
        o9: Int? = null,
    ) = Metrics.DayMacroGrams(
        date = d(day),
        proteinG = protein, carbsG = carbs, fatG = fat,
        fiberG = fiber, solubleFiberG = soluble, insolubleFiberG = insoluble,
        sugarsG = sugars, addedSugarsG = added, starchG = starch,
        satFatG = sat, monoFatG = mono, polyFatG = poly, transFatG = trans,
        cholesterolMg = chol, omega3G = o3, omega6G = o6, omega9G = o9,
    )

    @Test fun dayWithCarbsButNoSubMacrosIsAGapNotAllOther() {
        val day = grams(1, carbs = 100) // food logged, no detail
        assertFalse(day.hasCarbDetail)
        assertNull(Metrics.carbStack(day))
    }

    @Test fun carbRemainderIsNeverNegative() {
        // Known parts exceed carbs → other floors at 0
        val day = grams(1, carbs = 40, fiber = 10, sugars = 20, starch = 20)
        val s = Metrics.carbStack(day)!!
        assertEquals(0.0, s.otherG, 1e-9)
        assertTrue(s.otherG >= 0)
        assertTrue(s.total >= s.carbsG - 1e-9 || s.otherG == 0.0)
    }

    @Test fun carbStackSplitsFiberWhenBothKnown() {
        val day = grams(1, carbs = 100, soluble = 5, insoluble = 10, starch = 40, sugars = 20, added = 8)
        val s = Metrics.carbStack(day)!!
        assertTrue(s.splitFiber)
        assertTrue(s.showAdded)
        assertEquals(5.0, s.solubleFiberG, 1e-9)
        assertEquals(10.0, s.insolubleFiberG, 1e-9)
        assertEquals(0.0, s.fiberUnspecifiedG, 1e-9)
        assertEquals(40.0, s.starchG, 1e-9)
        assertEquals(12.0, s.sugarsG, 1e-9) // 20 − 8 added
        assertEquals(8.0, s.addedSugarsG, 1e-9)
        assertEquals(25.0, s.otherG, 1e-9) // 100 − 5 − 10 − 40 − 12 − 8
    }

    @Test fun carbStackUsesTotalFiberWhenSplitUnknown() {
        val day = grams(1, carbs = 80, fiber = 12, sugars = 18)
        val s = Metrics.carbStack(day)!!
        assertFalse(s.splitFiber)
        assertFalse(s.showAdded)
        assertEquals(12.0, s.fiberUnspecifiedG, 1e-9)
        assertEquals(18.0, s.sugarsG, 1e-9)
        assertEquals(0.0, s.addedSugarsG, 1e-9)
        assertEquals(50.0, s.otherG, 1e-9)
    }

    @Test fun fatRemainderNeverNegativeAndGapWithoutDetail() {
        assertNull(Metrics.fatStack(grams(1, fat = 50)))
        val day = grams(1, fat = 30, sat = 10, mono = 10, poly = 8, trans = 1)
        val s = Metrics.fatStack(day)!!
        assertEquals(1.0, s.otherG, 1e-9) // 30 − 29
        val over = Metrics.fatStack(grams(2, fat = 10, sat = 8, mono = 5))!!
        assertEquals(0.0, over.otherG, 1e-9)
    }

    @Test fun dailyStacksLeaveGapsForDaysWithoutDetail() {
        val byDate = mapOf(
            d(1) to grams(1, carbs = 100, fiber = 10),
            d(2) to grams(2, carbs = 90), // food, no detail → gap
            d(3) to grams(3, carbs = 80, sugars = 15),
        )
        val stacks = Metrics.dailyCarbStacks(byDate, d(1), d(3))
        assertEquals(3, stacks.size)
        assertTrue(stacks[0] != null)
        assertNull(stacks[1])
        assertTrue(stacks[2] != null)
    }

    @Test fun weeklyAveragesOnlyDaysWithDetail() {
        // Week of Sep 28 … Oct 4; Oct 5 next Monday
        val byDate = mapOf(
            d(1) to grams(1, carbs = 100, fiber = 10), // Thu
            d(2) to grams(2, carbs = 80),              // Fri, no detail — skipped
            d(8) to grams(8, carbs = 60, fiber = 6),    // next week
        )
        val weeks = Metrics.weeklyCarbAverages(byDate, d(1), d(8))
        assertEquals(2, weeks.size)
        assertEquals(10.0, weeks[0]!!.fiberUnspecifiedG, 1e-9)
        assertEquals(100.0, weeks[0]!!.carbsG, 1e-9)
        assertEquals(6.0, weeks[1]!!.fiberUnspecifiedG, 1e-9)
    }

    @Test fun coverageLabelSignalsPartialDetail() {
        val byDate = mapOf(
            d(1) to grams(1, carbs = 100, fiber = 5),
            d(2) to grams(2, carbs = 90),
            d(3) to grams(3, carbs = 80, sugars = 10),
        )
        val cov = Metrics.carbCoverage(byDate)
        assertEquals(2, cov.withDetail)
        assertEquals(3, cov.withFood)
        assertEquals("Detail on 2 of 3 days", cov.label)
        assertEquals("2 days", Metrics.carbCoverage(mapOf(d(1) to grams(1, carbs = 10, fiber = 1), d(2) to grams(2, carbs = 10, sugars = 1))).label)
        assertEquals("No food days", Metrics.carbCoverage(emptyMap()).label)
    }

    @Test fun lipidSummaryNullWithoutOmegaOrChol() {
        assertNull(Metrics.lipidSummary(mapOf(d(1) to grams(1, carbs = 50, fiber = 5))))
        val byDate = mapOf(
            d(1) to grams(1, o3 = 1, o6 = 4, chol = 200),
            d(2) to grams(2, o3 = 1, o6 = 2),
            d(3) to grams(3, chol = 100),
        )
        val s = Metrics.lipidSummary(byDate)!!
        assertEquals(1.0, s.avgOmega3G!!, 1e-9) // (1+1)/2
        assertEquals(3.0, s.avgOmega6G!!, 1e-9) // (4+2)/2
        assertEquals(3.0, s.omega6to3!!, 1e-9)  // 3/1
        assertEquals(150.0, s.avgCholesterolMg!!, 1e-9)
        assertEquals(2, s.daysWithOmega)
        assertEquals(2, s.daysWithChol)
        assertEquals(3, s.daysWithFood)
        // Averages ignore days that left the field unknown (not divided by all food days).
        assertEquals("Detail on 2 of 3 days", Metrics.lipidCoverageLabel(s))
        assertEquals(
            listOf("ω6:3 avg", "mg chol · avg", "g ω3 · avg", "g ω6 · avg"),
            Metrics.lipidStatLabels(s),
        )
    }

    @Test fun lipidCardShowsFiveStatsWhenOmega9Present() {
        // Repro for the phone overflow: five Stat columns in one Row crushed ω9 off-screen.
        val byDate = mapOf(
            d(1) to grams(1, o3 = 2, o6 = 15, o9 = 4, chol = 45),
            d(2) to grams(2, o3 = 2, o6 = 15, o9 = 4, chol = 45),
            d(3) to grams(3, carbs = 50), // food day without lipid detail
            d(4) to grams(4, carbs = 40),
            d(5) to grams(5, carbs = 30),
        )
        val s = Metrics.lipidSummary(byDate)!!
        assertEquals(7.5, s.omega6to3!!, 1e-9)
        assertEquals(45.0, s.avgCholesterolMg!!, 1e-9)
        assertEquals(2.0, s.avgOmega3G!!, 1e-9)
        assertEquals(15.0, s.avgOmega6G!!, 1e-9)
        assertEquals(4.0, s.avgOmega9G!!, 1e-9)
        assertEquals(2, s.daysWithOmega)
        assertEquals(2, s.daysWithChol)
        assertEquals(5, s.daysWithFood)
        assertEquals("Detail on 2 of 5 days", Metrics.lipidCoverageLabel(s))
        assertEquals(
            listOf("ω6:3 avg", "mg chol · avg", "g ω3 · avg", "g ω6 · avg", "g ω9 · avg"),
            Metrics.lipidStatLabels(s),
        )
        assertEquals(5, Metrics.lipidStatLabels(s).size)
    }

    @Test fun lipidCoverageLabelOmegaOrCholOnly() {
        val omegaOnly = Metrics.lipidSummary(mapOf(d(1) to grams(1, o3 = 1, o6 = 2), d(2) to grams(2)))!!
        assertEquals("Omega on 1 of 2 days", Metrics.lipidCoverageLabel(omegaOnly))
        val cholOnly = Metrics.lipidSummary(mapOf(d(1) to grams(1, chol = 10), d(2) to grams(2), d(3) to grams(3)))!!
        assertEquals("Cholesterol on 1 of 3 days", Metrics.lipidCoverageLabel(cholOnly))
    }

    @Test fun gatingFlagsMatchDetailPresence() {
        val bare = grams(1, protein = 20, carbs = 40, fat = 10)
        assertFalse(bare.hasAnyDetail)
        assertTrue(grams(1, fiber = 5).hasAnyDetail)
        assertTrue(grams(1, sat = 3).hasAnyDetail)
        assertTrue(grams(1, chol = 50).hasAnyDetail)
        assertTrue(grams(1, o3 = 1).hasAnyDetail)
    }
}
