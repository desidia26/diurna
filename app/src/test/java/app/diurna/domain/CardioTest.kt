package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CardioTest {
    private val mon = LocalDate.of(2026, 10, 12)

    private fun piece(
        day: Long, logged: Long, distance: Int? = null, time: Double? = null, split: Double? = null,
        best: Double? = null, watts: Int? = null, kcal: Int? = null, exercise: Long = 1, style: Cardio.Style = Cardio.Style.ROW,
    ) = Cardio.Piece(mon.plusDays(day), exercise, "Rowing machine", logged, distance, time, split, best, watts, kcal, style = style)

    @Test fun splitIsTimePer500Metres() {
        assertEquals(139.11, Cardio.split(907.0, 3260)!!, 0.01) // 2:19.1
        assertEquals(120.0, Cardio.split(480.0, 2000)!!, 1e-9)
        assertNull(Cardio.split(null, 2000))
        assertNull(Cardio.split(480.0, 0))
    }

    @Test fun theMachinesSplitWinsOverTheWorkedOutOne() {
        // Ergatta shows 2:10 (power-based) where time ÷ distance gives 2:19.
        assertEquals(130.0, Cardio.effectiveSplit(130.0, 907.0, 3260)!!, 1e-9)
        assertEquals(139.11, Cardio.effectiveSplit(null, 907.0, 3260)!!, 0.01)
    }

    @Test fun clocksFormatAndParse() {
        assertEquals("15:07", Cardio.clock(907.0))
        assertEquals("2:10", Cardio.clock(130.0))
        assertEquals("1:02:03", Cardio.clock(3723.0))
        assertEquals("2:19", Cardio.clock(139.11))
        assertEquals("0:05", Cardio.clock(5.0))
        assertEquals("2:10/500m", Cardio.Pace.PER_500M.text(130.0))
        assertEquals("3,260 m", Cardio.distanceText(3260))

        assertEquals(907.0, Cardio.parseClock("15:07")!!, 1e-9)
        assertEquals(130.5, Cardio.parseClock(" 2:10.5 ")!!, 1e-9)
        assertEquals(3723.0, Cardio.parseClock("1:02:03")!!, 1e-9)
        assertEquals(907.0, Cardio.parseClock("907")!!, 1e-9)
        listOf("", "abc", "15:60", "1:2:3:4", "15:", ":07", "1.5:00", "-2:10", "2:1x").forEach { assertNull(it, Cardio.parseClock(it)) }
    }

    @Test fun paceConvertsToEachStylesUnit() {
        // 5.0 km in 26:00 is 156 s/500m stored, 5:12/km, 8:22/mi, 11.5 km/h.
        val split = Cardio.split(1560.0, 5000)!!
        assertEquals(156.0, split, 1e-9)
        assertEquals("5:12/km", Cardio.Pace.PER_KM.text(split))
        assertEquals("8:22/mi", Cardio.Pace.PER_MILE.text(split))
        assertEquals("11.5 km/h", Cardio.Pace.KMH.text(split))
        assertEquals("7.2 mph", Cardio.Pace.MPH.text(split))
        assertNull(Cardio.Pace.NONE.text(split))
        Cardio.Pace.entries.filter { it != Cardio.Pace.NONE }.forEach { p ->
            assertEquals(p.name, split, p.toSplit(p.fromSplit(split)!!)!!, 1e-9)
        }
    }

    @Test fun stylesFallBackToTimeOnly() {
        assertEquals(Cardio.Style.ROW, Cardio.Style.of("500m"))
        assertEquals(Cardio.Style.RIDE_MI, Cardio.Style.of("mph"))
        assertEquals(Cardio.Style.TIME, Cardio.Style.of(null))
        assertEquals(Cardio.Style.TIME, Cardio.Style.of("nonsense"))
        assertEquals(Cardio.Style.entries.size, Cardio.Style.entries.map { it.key }.toSet().size)
    }

    @Test fun distancesAndDurationsReadInTheStylesUnits() {
        assertEquals("3,260 m", Cardio.distanceText(3260, Cardio.Style.ROW))
        assertEquals("5.0 km", Cardio.distanceText(5000, Cardio.Style.RUN_KM))
        assertEquals("3.1 mi", Cardio.distanceText(5000, Cardio.Style.RUN_MI))
        assertEquals("30 min", Cardio.durationText(1800.0))
        assertEquals("15:07", Cardio.durationText(907.0))
        assertEquals("1:00:00", Cardio.durationText(3600.0))
    }

    @Test fun summaryListsWhatIsKnownAndTheStyleShows() {
        assertEquals("3,260 m · 15:07 · 2:10/500m · 158 W", Cardio.summary(Cardio.Style.ROW, 3260, 907.0, 130.0, 158, 120))
        assertEquals("5.0 km · 26 min · 5:12/km", Cardio.summary(Cardio.Style.RUN_KM, 5000, 1560.0, 156.0, kcal = 300))
        assertEquals("10.0 mi · 37 min · 16.2 mph · 180 W", Cardio.summary(Cardio.Style.RIDE_MI, 16093, 2220.0, Cardio.split(2220.0, 16093), 180))
        assertEquals("30 min · 250 kcal", Cardio.summary(Cardio.Style.TIME, null, 1800.0, null, kcal = 250))
        assertEquals("15:07", Cardio.summary(Cardio.Style.ROW, null, 907.0, null))
        assertEquals("", Cardio.summary(Cardio.Style.ROW, null, null, null))
    }

    @Test fun headlinesForTheLockScreen() {
        assertEquals("3,260 m · 2:10/500m", Cardio.headline(Cardio.Style.ROW, 3260, 907.0, 130.0, 120))
        assertEquals("5.0 km · 5:12/km", Cardio.headline(Cardio.Style.RUN_KM, 5000, 1560.0, null, null))
        assertEquals("30 min · 250 kcal", Cardio.headline(Cardio.Style.TIME, null, 1800.0, null, 250))
        assertEquals("30 min", Cardio.headline(Cardio.Style.TIME, null, 1800.0, null, null))
    }

    @Test fun formatsGroupByRoundedMinutesOrElseDistance() {
        assertEquals(Cardio.Format("t15", "15 min"), Cardio.Format.of(907.0, 3260))
        assertEquals(Cardio.Format("t15", "15 min"), Cardio.Format.of(898.0, 3100))
        assertEquals(Cardio.Format("d2000", "2,000 m"), Cardio.Format.of(null, 2000))
        assertEquals(Cardio.Format("d5000", "5.0 km"), Cardio.Format.of(null, 5000, Cardio.Style.RUN_KM))
        assertNull(Cardio.Format.of(null, null))
    }

    @Test fun sessionTotalsOnePieceKeepsItsOwnNumbers() {
        val s = Cardio.sessions(listOf(piece(0, 10, 3260, 907.0, 130.0, 104.0, 158, 120))).single()
        assertEquals(3260, s.distanceM)
        assertEquals(907.0, s.durationS!!, 1e-9)
        assertEquals(130.0, s.splitS!!, 1e-9)
        assertEquals(104.0, s.bestSplitS!!, 1e-9)
        assertEquals(158, s.watts)
        assertEquals(120, s.kcal)
        assertEquals("15 min", s.format?.label)
    }

    @Test fun intervalsAddUpAndAverageByDistanceAndTime() {
        val s = Cardio.sessions(listOf(
            piece(0, 10, 1000, 240.0, watts = 200),  // 2:00
            piece(0, 10, 500, 150.0, watts = 100),   // 3:00
            piece(0, 10),                              // empty piece ignored
        )).single()
        assertEquals(1500, s.distanceM)
        assertEquals(390.0, s.durationS!!, 1e-9)
        assertEquals((120.0 * 1000 + 150.0 * 500) / 1500, s.splitS!!, 1e-9)
        assertEquals(120.0, s.bestSplitS!!, 1e-9)
        assertEquals(162, s.watts) // time-weighted: (200×240 + 100×150) / 390 = 161.5
    }

    @Test fun emptySessionsAreLeftOut() {
        assertTrue(Cardio.sessions(listOf(piece(0, 10), piece(1, 11, kcal = 50))).isEmpty())
    }

    @Test fun prsCompareLikeWithLikeAndTheFirstIsABaseline() {
        val sessions = Cardio.sessions(listOf(
            piece(0, 1, 3260, 907.0, 130.0, watts = 158),   // baseline 15 min
            piece(2, 2, 3300, 905.0, 131.0, watts = 158),   // farther: distance PR only (tie watts, slower split)
            piece(4, 3, 3300, 903.0, 129.0, watts = 160),   // tie distance; faster split, more watts
            piece(5, 4, 2000, 470.0, 117.5, watts = 220),   // first 8 min piece: baseline, not a PR
            piece(6, 5, 3000, 900.0, 140.0, watts = 140, exercise = 2), // other exercise baseline
        ))
        val prs = Cardio.prs(sessions)
        assertEquals(listOf(2L, 3L), prs.map { it.session.loggedId })
        assertEquals(Triple(true, false, false), prs[0].let { Triple(it.distance, it.split, it.watts) })
        assertEquals(Triple(false, true, true), prs[1].let { Triple(it.distance, it.split, it.watts) })
    }

    @Test fun timeOnlyPrsAreMoreCaloriesInTheSameMinutes() {
        val t = Cardio.Style.TIME
        val sessions = Cardio.sessions(listOf(
            piece(0, 1, time = 1800.0, kcal = 250, style = t), // baseline 30 min
            piece(1, 2, time = 1790.0, kcal = 240, style = t), // fewer: not a PR
            piece(2, 3, time = 1810.0, kcal = 260, style = t), // more: PR
        ))
        val prs = Cardio.prs(sessions)
        assertEquals(listOf(3L), prs.map { it.session.loggedId })
        assertTrue(prs.single().kcal)
        // Calories don't make a PR where there's a distance to beat.
        val rows = Cardio.sessions(listOf(piece(0, 1, 3260, 907.0, 130.0, kcal = 100), piece(1, 2, 3200, 907.0, 131.0, kcal = 200)))
        assertTrue(Cardio.prs(rows).isEmpty())
    }

    @Test fun weeklyTotalsFillEmptyWeeks() {
        val sessions = Cardio.sessions(listOf(piece(0, 1, 3260, 907.0), piece(3, 2, 3000, 900.0), piece(14, 3, 2000, 480.0)))
        val weeks = Cardio.weeklyTotals(sessions, mon, mon.plusDays(16))
        assertEquals(listOf(6260, 0, 2000), weeks.map { it.metres })
        assertEquals(listOf(1807.0, 0.0, 480.0), weeks.map { it.seconds })
        assertEquals(mon, weeks.first().monday)
    }

    @Test fun cleanDropsZerosAndMisreads() {
        val p = CardioPiece(0, 907.0, 0.0, 5000.0, 99999, 120, 300).clean()
        assertEquals(CardioPiece(durationS = 907.0, kcal = 120), p)
        assertTrue(CardioPiece().isEmpty)
    }
}
