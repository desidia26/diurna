package app.diurna.domain

import app.diurna.data.SleepPeriodEntity
import app.diurna.domain.SleepRules.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SleepViewTest {
    private val thu = LocalDate.of(2026, 11, 26)

    private fun night(
        date: String = "2026-11-26", type: String = "main", origin: String = SleepRules.OURA,
        bed: String = "2026-11-25 22:10", woke: String? = "2026-11-26 06:15", up: String = "2026-11-26 06:50",
        inBed: Long = 8 * 3600 + 5 * 60, asleep: Long? = 7 * 3600L,
    ) = SleepPeriodEntity(
        source = "health_connect", origin = origin, sourceId = "$origin$bed", date = date, type = type,
        bedStart = bed, wokeAt = woke, bedEnd = up, timeInBedS = inBed, totalSleepS = asleep, fetchedAt = 0,
    )

    @Test fun durationsReadAsHoursAndMinutes() {
        assertEquals("7h 00m", SleepView.hm(7 * 3600))
        assertEquals("7h 21m", SleepView.hm(7 * 3600 + 20 * 60 + 40))
        assertEquals("25m", SleepView.hm(25 * 60))
    }

    @Test fun aDayKeepsOnlyThePickedSourceAndSplitsNaps() {
        val nap = night(type = "nap", bed = "2026-11-26 14:10", woke = "2026-11-26 14:35", up = "2026-11-26 14:40", inBed = 1500, asleep = 1500)
        val watch = night(origin = "com.watch")
        val earlier = night(date = "2026-11-25")
        val d = SleepView.day(listOf(earlier, watch, night(), nap), thu, SleepRules.OURA)
        assertEquals(night(), d.main)
        assertEquals(listOf(nap), d.naps)
    }

    @Test fun mainNightRow() {
        val row = SleepView.row(SleepView.Day(night(), emptyList()), thu, thu)
        assertEquals("7h 00m asleep", row.title)
        assertEquals("22:10 → woke 06:15 · up 06:50", row.detail)
    }

    @Test fun wokeAndUpTogetherShowOnce() {
        val row = SleepView.row(SleepView.Day(night(up = "2026-11-26 06:15"), emptyList()), thu, thu)
        assertEquals("22:10 → woke 06:15", row.detail)
    }

    @Test fun withoutStagesItsTimeInBed() {
        val n = night(bed = "2026-11-25 23:55", woke = null, up = "2026-11-26 06:35", inBed = 6 * 3600 + 40 * 60, asleep = null)
        val row = SleepView.row(SleepView.Day(n, emptyList()), thu, thu)
        assertEquals("6h 40m in bed", row.title)
        assertEquals("23:55 → 06:35 · no stages shared", row.detail)
    }

    @Test fun napsAddToTheRow() {
        val nap = night(type = "nap", bed = "2026-11-26 14:10", woke = "2026-11-26 14:35", up = "2026-11-26 14:40", inBed = 1500, asleep = 1500)
        val row = SleepView.row(SleepView.Day(night(), listOf(nap)), thu, thu)
        assertEquals("7h 00m asleep + 25m nap", row.title)
        assertEquals("22:10 → woke 06:15 · up 06:50 · nap 14:10 → 14:40", row.detail)
        val napOnly = SleepView.row(SleepView.Day(null, listOf(nap)), thu, thu)
        assertEquals("25m nap", napOnly.title)
    }

    @Test fun nothingYetTodayIsNotSyncedButAnOldDayIsNoData() {
        assertEquals("Not synced yet", SleepView.row(SleepView.Day(null, emptyList()), thu, thu).title)
        assertEquals("No sleep recorded", SleepView.row(SleepView.Day(null, emptyList()), thu.minusDays(3), thu).title)
    }

    @Test fun aFutureDayIsNotWaitingOnASync() {
        assertEquals("Not yet", SleepView.row(SleepView.Day(null, emptyList()), thu.plusDays(1), thu).title)
        assertFalse(SleepView.syncPending(thu.plusDays(1), thu))
        assertTrue(SleepView.syncPending(thu, thu))
        assertTrue(SleepView.syncPending(thu.minusDays(1), thu))
    }

    @Test fun summaryAndChipUseTheMainNight() {
        val d = SleepView.Day(night(), emptyList())
        assertEquals("7h 00m sleep", SleepView.summary(d))
        assertEquals("7h 00m", SleepView.chip(d))
        assertNull(SleepView.summary(SleepView.Day(null, emptyList())))
        assertNull(SleepView.chip(SleepView.Day(null, emptyList())))
    }

    @Test fun awakeAfterWakingIsWokeToUp() {
        assertEquals(35 * 60L, SleepView.awakeAfterWaking(night()))
        assertNull(SleepView.awakeAfterWaking(night(up = "2026-11-26 06:15")))
        assertNull(SleepView.awakeAfterWaking(night(woke = null)))
    }

    @Test fun sessionLengthRunsBedToUp() {
        assertEquals(8 * 3600L + 40 * 60, SleepView.lengthS(night()))
        assertEquals(8 * 3600L + 5 * 60, SleepView.lengthS(night(woke = null, up = "2026-11-26 06:15")))
    }

    @Test fun sessionLengthIsRealTimeOnTheNightTheClocksGoBack() {
        // 22:00 EDT to woke 06:30 EST is 9h 30m real; up at 07:00. The clock says 9h.
        val n = night(date = "2026-11-01", bed = "2026-10-31 22:00", woke = "2026-11-01 06:30", up = "2026-11-01 07:00", inBed = 9 * 3600L + 30 * 60)
        assertEquals(10 * 3600L, SleepView.lengthS(n))
    }

    @Test fun slotsBreakWhereTheRingSentNothing() {
        val pts = listOf(Point(0, 60.0), Point(300, 58.0), Point(1200, 55.0))
        assertEquals(listOf(60.0, 58.0, null, null, 55.0), SleepView.slots(pts))
        assertEquals(emptyList<Double?>(), SleepView.slots(emptyList()))
    }
}
