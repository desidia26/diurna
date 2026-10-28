package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MetricsAminoTest {
    @Test fun aminoSummaryAveragesKnownDaysOnly() {
        val d1 = LocalDate.of(2026, 10, 1)
        val d2 = LocalDate.of(2026, 10, 2)
        val d3 = LocalDate.of(2026, 10, 3)
        val byDate = mapOf(
            d1 to Metrics.DayMacroGrams(d1, 20, 30, 10, lysMg = 1000, hisMg = 200, aminoPartial = true),
            d2 to Metrics.DayMacroGrams(d2, 20, 30, 10, lysMg = 2000),
            d3 to Metrics.DayMacroGrams(d3, 20, 30, 10), // no aminos
        )
        val s = Metrics.aminoSummary(byDate)!!
        assertEquals(1500, s.average.lysMg)
        assertEquals(200, s.average.hisMg)
        assertEquals(2, s.daysWithAminos)
        assertEquals(3, s.daysWithFood)
        assertEquals(1, s.daysWithPartial)
        assertTrue(s.coverageLabel.contains("2 of 3"))
        assertTrue(s.coverageLabel.contains("1 partial"))
        assertNull(Metrics.aminoSummary(mapOf(d3 to byDate.getValue(d3))))
    }
}
