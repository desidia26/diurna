package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MetricsTest {
    private val oct1: LocalDate = LocalDate.of(2026, 10, 1)
    private fun d(day: Int): LocalDate = oct1.plusDays(day - 1L)

    // ---- Weight ----

    @Test fun averageNeedsThreeWeighInsInItsWindow() {
        val w = mapOf(d(1) to 180.0, d(3) to 182.0, d(5) to 184.0)
        val avg = Metrics.rollingAverage(w, d(1), d(12))
        assertNull("one weigh-in isn't an average", avg[0])
        assertNull(avg[3])
        assertEquals(182.0, avg[4]!!, 1e-9)
        assertEquals("Oct 7 still sees Oct 1", 182.0, avg[6]!!, 1e-9)
        assertNull("Oct 8 has dropped Oct 1", avg[7])
        assertNull("a gap leaves the line broken", avg[11])
    }

    @Test fun windowReachesBeforeTheRange() {
        val w = mapOf(d(1) to 180.0, d(2) to 181.0, d(7) to 185.0)
        assertEquals(182.0, Metrics.rollingAverage(w, d(7), d(7)).single()!!, 1e-9)
    }

    @Test fun changeIsLastKnownMinusFirstKnown() {
        assertEquals(-1.5, Metrics.change(listOf(null, 184.0, null, 182.5, null))!!, 1e-9)
        assertNull("one point is no change", Metrics.change(listOf(null, 184.0, null)))
        assertNull(Metrics.change(emptyList()))
    }

    // ---- Ratings ----

    @Test fun ratingAveragesSkipUnratedDays() {
        val s = Metrics.ratingSummary(listOf(
            Metrics.DayRating(d(1), 4, 2),
            Metrics.DayRating(d(2), null, null),
            Metrics.DayRating(d(3), 2, null),
        ))
        assertEquals(3.0, s.mood!!, 1e-9)
        assertEquals(2.0, s.energy!!, 1e-9)
        assertEquals(2, s.rated)
        assertEquals(3, s.days)
        assertNull(Metrics.ratingSummary(listOf(Metrics.DayRating(d(1), null, null))).mood)
    }

    // ---- Lifting ----

    @Test fun e1rmBounds() {
        assertEquals(100.0, Metrics.e1rm(100.0, 1)!!, 1e-9)
        assertEquals(216.0, Metrics.e1rm(180.0, 6)!!, 1e-9)
        assertEquals(140.0, Metrics.e1rm(100.0, 12)!!, 1e-9)
        assertNull("Epley is unreliable past 12 reps", Metrics.e1rm(100.0, 13))
        assertNull(Metrics.e1rm(100.0, 0))
        assertNull(Metrics.e1rm(null, 5))
        assertNull("bodyweight sets have no weight", Metrics.e1rm(0.0, 5))
    }

    private var nextLogged = 1L
    private fun session(date: LocalDate, exercise: Long, vararg sets: Pair<Double?, Int?>): List<Metrics.SetEntry> {
        val id = nextLogged++
        return sets.map { (w, r) -> Metrics.SetEntry(date, exercise, "Ex $exercise", id, w, r) }
    }

    @Test fun volumeCountsOnlyCompleteSets() {
        val s = Metrics.sessions(session(d(1), 1, 100.0 to 10, 100.0 to null, null to 8, 110.0 to 5)).single()
        assertEquals(1550.0, s.volume, 1e-9)
        assertEquals(110.0, s.topWeight!!, 1e-9)
        assertEquals("the lighter set with more reps estimates higher", 100.0 * (1 + 10 / 30.0), s.bestE1rm!!, 1e-9)
    }

    @Test fun sessionsWithNoRepsAreLeftOut() {
        val planned = session(d(1), 1, 100.0 to null, null to null)
        val done = session(d(2), 1, 100.0 to 5)
        assertEquals(listOf(d(2)), Metrics.sessions(planned + done).map { it.date })
    }

    @Test fun prsBeatEveryEarlierSession() {
        val s = Metrics.sessions(
            session(d(1), 1, 175.0 to 5) +
                session(d(8), 1, 175.0 to 6) + // more reps, same weight: e1RM PR only
                session(d(15), 1, 175.0 to 6) + // tie: nothing
                session(d(22), 1, 185.0 to 3) + // heavier but lower e1RM: weight PR only
                session(d(22), 2, 50.0 to 10), // first time for exercise 2: baseline
        )
        val prs = Metrics.prs(s)
        assertEquals(listOf(d(8), d(22)), prs.map { it.session.date })
        assertEquals(true to false, prs[0].e1rm to prs[0].weight)
        assertEquals(false to true, prs[1].e1rm to prs[1].weight)
    }

    @Test fun mostLoggedBreaksTiesByRecency() {
        val s = Metrics.sessions(
            session(d(1), 1, 100.0 to 5) + session(d(3), 1, 100.0 to 5) +
                session(d(2), 2, 50.0 to 5) + session(d(4), 2, 50.0 to 5) +
                session(d(5), 3, 20.0 to 5) +
                session(d(20), 3, 20.0 to 5), // outside the range
        )
        assertEquals(listOf(2L, 1L, 3L), Metrics.mostLogged(s, d(1), d(10)).map { it.exerciseId })
        assertEquals(1, Metrics.mostLogged(s, d(1), d(10), n = 1).size)
    }

    @Test fun weeklyVolumeUsesMondayWeeksAcrossMonths() {
        // Sep 28 2026 is a Monday; Oct 1 is a Thursday in that week.
        val s = Metrics.sessions(session(d(1), 1, 100.0 to 10) + session(d(4), 1, 100.0 to 5) + session(d(6), 1, 100.0 to 1))
        val weeks = Metrics.weeklyVolume(s, d(1), d(13))
        assertEquals(listOf(LocalDate.of(2026, 9, 28), d(5), d(12)), weeks.map { it.monday })
        assertEquals(listOf(1500.0, 100.0, 0.0), weeks.map { it.volume })
        assertEquals("a range starting mid-week still gets the whole first week", 1500.0, Metrics.weeklyVolume(s, d(4), d(13)).first().volume, 1e-9)
    }

    @Test fun rangeStarts() {
        assertEquals(d(1), Metrics.Range.W4.start(d(28), null))
        assertEquals("All starts at the first day with data", d(3), Metrics.Range.ALL.start(d(28), d(3)))
        assertEquals(d(28), Metrics.Range.ALL.start(d(28), null))
    }
}
