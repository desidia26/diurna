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
    private val headache = Def(6, "Headache", Kind.LOG)

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

    // Log metrics: any number of timed entries a day.

    @Test fun logKindRoundTripsItsKey() {
        assertEquals(Kind.LOG, Kind.of("log"))
        assertEquals("Log", Kind.LOG.label)
    }

    @Test fun aLogNeverHoldsTheCheckInOpen() {
        assertEquals("nothing logged is a real answer", listOf(mood), DailyMetrics.missing(listOf(mood, headache), emptyMap()))
        assertTrue(DailyMetrics.complete(listOf(headache), emptyMap()))
        assertNull("a log takes entries, not a value", DailyMetrics.cleanValue(headache, 1.0))
    }

    @Test fun summaryCountsLogEntriesAndSkipsEmptyLogs() {
        val defs = listOf(mood, headache)
        assertEquals("Not rated yet", DailyMetrics.summary(defs, emptyMap(), mapOf(6L to 0)))
        assertEquals("Mood 4 good", DailyMetrics.summary(defs, mapOf(1L to Value(4.0))))
        assertEquals("Mood 4 good · Headache 2", DailyMetrics.summary(defs, mapOf(1L to Value(4.0)), mapOf(6L to 2)))
        assertEquals("entries alone fill the card", "Mood not rated · Headache 1", DailyMetrics.summary(defs, emptyMap(), mapOf(6L to 1)))
        assertEquals(
            "archived logs stay off the card",
            "Not rated yet",
            DailyMetrics.summary(listOf(headache.copy(archived = true)), emptyMap(), mapOf(6L to 3)),
        )
    }

    @Test fun timesAreTwentyFourHourHHmm() {
        assertEquals("09:05", DailyMetrics.cleanTime("09:05"))
        assertEquals("23:59", DailyMetrics.cleanTime(" 23:59 "))
        assertEquals("00:00", DailyMetrics.cleanTime("00:00"))
        assertNull(DailyMetrics.cleanTime("24:00"))
        assertNull(DailyMetrics.cleanTime("9:05"))
        assertNull(DailyMetrics.cleanTime("12:60"))
        assertNull(DailyMetrics.cleanTime("noon"))
        assertNull(DailyMetrics.cleanTime(null))
    }

    @Test fun notesAreTrimmedAndCapped() {
        assertNull(DailyMetrics.cleanNote(null))
        assertNull(DailyMetrics.cleanNote("  \n "))
        assertEquals("behind the eyes", DailyMetrics.cleanNote("  behind the eyes \n"))
        assertEquals("line breaks kept inside", "left side\nafter screen time", DailyMetrics.cleanNote("left side\nafter screen time"))
        assertEquals(DailyMetrics.NOTE_MAX, DailyMetrics.cleanNote("x".repeat(1000))!!.length)
    }

    @Test fun entriesReadEarliestFirstAndTiesKeepLoggedOrder() {
        val e = listOf(
            DailyMetrics.LogEntry(3, "14:40"),
            DailyMetrics.LogEntry(1, "09:12", "after coffee"),
            DailyMetrics.LogEntry(4, "09:12"),
            DailyMetrics.LogEntry(2, "21:00"),
        )
        assertEquals(listOf(1L, 4L, 3L, 2L), DailyMetrics.ordered(e).map { it.id })
        assertEquals("09:12 · after coffee", DailyMetrics.entryLine(e[1]))
        assertEquals("14:40", DailyMetrics.entryLine(e[0]))
    }

    @Test fun dailyCountsFillEveryDayInRange() {
        val mon = LocalDate.of(2026, 10, 12)
        val dates = listOf(mon.minusDays(1), mon, mon, mon.plusDays(2), mon.plusDays(2), mon.plusDays(2), mon.plusDays(9))
        assertEquals(listOf(2, 0, 3, 0), DailyMetrics.dailyCounts(dates, mon, mon.plusDays(3)))
        assertEquals(listOf(0), DailyMetrics.dailyCounts(emptyList(), mon, mon))
    }

    @Test fun weeklyCountsTotalEachMondayToSundayWeek() {
        val mon = LocalDate.of(2026, 10, 12)
        val dates = listOf(mon.minusDays(1), mon, mon.plusDays(6), mon.plusDays(6), mon.plusDays(15), mon.plusDays(30))
        assertEquals("the day before the range doesn't count", listOf(3, 0, 1), DailyMetrics.weeklyCounts(dates, mon, mon.plusDays(15)))
    }
}
