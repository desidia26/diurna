package app.diurna.domain

import app.diurna.domain.DailyMetrics.Def
import app.diurna.domain.DailyMetrics.Kind
import app.diurna.domain.DailyMetrics.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyMetricsTest {
    private val mood = Def(1, "Mood", Kind.SCALE, 5, DailyMetrics.moodWords)
    private val energy = Def(2, "Energy", Kind.SCALE, 5, DailyMetrics.energyWords)
    private val sleep = Def(3, "Sleep", Kind.NUMBER, unit = "h")
    private val meds = Def(4, "Meds", Kind.YES_NO)
    private val journal = Def(5, "Journal", Kind.TEXT)

    @Test fun summaryReadsLikeTheOldCheckIn() {
        val defs = listOf(mood, energy)
        assertEquals("Not rated yet", DailyMetrics.summary(defs, emptyMap()))
        assertEquals("Mood 4 good · Energy 2 low", DailyMetrics.summary(defs, mapOf(1L to Value(4.0), 2L to Value(2.0))))
        assertEquals("Mood 1 rough · Energy not rated", DailyMetrics.summary(defs, mapOf(1L to Value(1.0))))
    }

    @Test fun summaryCoversEveryKindAndSkipsArchivedAndUnwrittenText() {
        val defs = listOf(sleep, meds, journal, energy.copy(archived = true))
        val values = mapOf(3L to Value(7.5), 4L to Value(0.0), 2L to Value(3.0))
        assertEquals("Sleep 7.5 h · Meds no", DailyMetrics.summary(defs, values))
        assertEquals(
            "Sleep 7.5 h · Meds no · Journal written",
            DailyMetrics.summary(defs, values + (5L to Value(text = "walked"))),
        )
    }

    @Test fun textNeverHoldsTheCheckInOpenAndArchivedDontCount() {
        val defs = listOf(mood, journal, meds.copy(archived = true))
        assertEquals(listOf(mood), DailyMetrics.missing(defs, emptyMap()))
        assertTrue(DailyMetrics.complete(defs, mapOf(1L to Value(3.0))))
        assertTrue("nothing active to ask about", DailyMetrics.complete(listOf(journal), emptyMap()))
        assertFalse(DailyMetrics.complete(listOf(meds), mapOf(4L to Value(text = "x"))))
    }

    @Test fun valuesAreCleanedToFitTheMetric() {
        assertEquals(3.0, DailyMetrics.cleanValue(mood, 3.2)!!, 0.0)
        assertNull("off the scale", DailyMetrics.cleanValue(mood, 9.0))
        assertNull(DailyMetrics.cleanValue(mood, 0.0))
        assertEquals(10.0, DailyMetrics.cleanValue(Def(9, "Pain", Kind.SCALE, 10), 10.0)!!, 0.0)
        assertEquals(-2.5, DailyMetrics.cleanValue(sleep, -2.5)!!, 0.0)
        assertNull(DailyMetrics.cleanValue(sleep, Double.NaN))
        assertNull(DailyMetrics.cleanValue(sleep, 1e12))
        assertNull("yes/no is 1 or 0 only", DailyMetrics.cleanValue(meds, 0.5))
        assertNull(DailyMetrics.cleanValue(journal, 1.0))
        assertNull(DailyMetrics.cleanText("   "))
        assertEquals("ok", DailyMetrics.cleanText("  ok \n"))
        assertEquals(DailyMetrics.TEXT_MAX, DailyMetrics.cleanText("x".repeat(5000))!!.length)
    }

    @Test fun definitionProblems() {
        assertNull(DailyMetrics.problem(sleep, listOf("Mood")))
        assertEquals("Give it a name.", DailyMetrics.problem(sleep.copy(name = "  "), emptyList()))
        assertEquals("There's already a metric called mood.", DailyMetrics.problem(sleep.copy(name = " mood "), listOf("Mood")))
        assertTrue(DailyMetrics.problem(sleep.copy(name = "x".repeat(41)), emptyList())!!.contains("40"))
        assertTrue(DailyMetrics.problem(Def(0, "Pain", Kind.SCALE, 11), emptyList())!!.contains("3 to 10"))
        assertTrue(DailyMetrics.problem(Def(0, "Pain", Kind.SCALE, 3, listOf("a", "b")), emptyList())!!.contains("each step"))
        assertTrue(DailyMetrics.problem(Def(0, "Pain", Kind.SCALE, 3, listOf("a", "b", "x".repeat(17))), emptyList())!!.contains("16"))
        assertTrue(DailyMetrics.problem(sleep.copy(unit = "x".repeat(13)), emptyList())!!.contains("12"))
        // Steps and words only matter for a scale.
        assertNull(DailyMetrics.problem(Def(0, "Meds", Kind.YES_NO, 99), emptyList()))
    }

    @Test fun wordsRoundTripKeepingBlankSteps() {
        assertEquals(listOf("low", "", "high"), DailyMetrics.parseWords(DailyMetrics.joinWords(listOf(" low ", "", "high"))))
        assertNull(DailyMetrics.joinWords(listOf("", " ")))
        assertEquals(emptyList<String>(), DailyMetrics.parseWords(null))
        val pain = Def(9, "Pain", Kind.SCALE, 3, listOf("low", "", "high"))
        assertEquals("2", DailyMetrics.display(pain, Value(2.0)))
        assertEquals("3 high", DailyMetrics.display(pain, Value(3.0)))
    }

    @Test fun numbersShowUpToTwoDecimals() {
        assertEquals("8", DailyMetrics.number(8.0))
        assertEquals("7.5", DailyMetrics.number(7.5))
        assertEquals("0.25", DailyMetrics.number(0.25))
        assertEquals("0.33", DailyMetrics.number(1.0 / 3))
        assertEquals("8", DailyMetrics.display(sleep.copy(unit = " "), Value(8.0)))
    }

    @Test fun weeklyYesShareCountsAnsweredDaysOnly() {
        val mon = LocalDate.of(2026, 10, 12)
        val values = mapOf(
            mon.minusDays(1) to 1.0, // before the range
            mon to 1.0, mon.plusDays(1) to 0.0, mon.plusDays(2) to 1.0, mon.plusDays(3) to 1.0,
            mon.plusDays(14) to 0.0,
        )
        val weeks = DailyMetrics.weeklyYesShare(values, mon, mon.plusDays(15))
        assertEquals(3, weeks.size)
        assertEquals(75.0, weeks[0]!!, 1e-9)
        assertNull("a week with no answers", weeks[1])
        assertEquals(0.0, weeks[2]!!, 1e-9)
    }

    @Test fun unknownKindReadsAsText() {
        assertEquals(Kind.TEXT, Kind.of("mystery"))
        assertEquals(Kind.YES_NO, Kind.of("yesno"))
    }
}
