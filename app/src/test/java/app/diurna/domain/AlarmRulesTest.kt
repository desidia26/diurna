package app.diurna.domain

import app.diurna.domain.AlarmRules.DayAlarmInputs
import app.diurna.domain.AlarmRules.Morning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AlarmRulesTest {
    private val tue = LocalDate.of(2026, 10, 13)
    private val mon = tue.minusDays(1)

    @Test fun locksAtTenPmTheDayBefore() {
        assertFalse(AlarmRules.isLocked(tue, mon.atTime(21, 59)))
        assertTrue(AlarmRules.isLocked(tue, mon.atTime(22, 0)))
        assertTrue(AlarmRules.isLocked(tue, tue.atTime(6, 0)))
        assertFalse(AlarmRules.isLocked(tue.plusDays(3), mon.atTime(23, 0)))
    }

    @Test fun overrideBeatsRoutine() {
        assertEquals("05:00", AlarmRules.resolve("05:00", "06:30"))
        assertEquals(AlarmRules.OFF, AlarmRules.resolve(AlarmRules.OFF, "06:30"))
        assertEquals("06:30", AlarmRules.resolve(null, "06:30"))
    }

    @Test fun frozenValueWinsOnlyAfterLock() {
        // Routine changed to 07:30 after the 06:30 alarm was armed.
        val before = AlarmRules.effective(tue, mon.atTime(21, 0), null, "07:30", frozen = "06:30")
        val after = AlarmRules.effective(tue, mon.atTime(22, 30), null, "07:30", frozen = "06:30")
        assertEquals("07:30", before)
        assertEquals("06:30", after)
    }

    @Test fun nextAlarmSkipsOffAndScannedDays() {
        val now = mon.atTime(7, 0)
        val inputs = mapOf(
            mon to DayAlarmInputs("06:30", scanned = false), // already passed
            tue to DayAlarmInputs(AlarmRules.OFF, scanned = false),
            tue.plusDays(1) to DayAlarmInputs("06:30", scanned = true),
        )
        val next = AlarmRules.nextAlarm(now) { inputs[it] ?: DayAlarmInputs("06:45", false) }
        assertEquals(tue.plusDays(2), next?.date)
        assertEquals(LocalTime.of(6, 45), next?.time)
    }

    @Test fun nextAlarmIsLaterTodayWhenNotPassed() {
        val next = AlarmRules.nextAlarm(mon.atTime(5, 0)) { DayAlarmInputs("06:30", false) }
        assertEquals(LocalDateTime.of(mon, LocalTime.of(6, 30)), next?.at)
    }

    @Test fun noAlarmWhenEveryDayIsOff() {
        assertNull(AlarmRules.nextAlarm(mon.atTime(5, 0)) { DayAlarmInputs(AlarmRules.OFF, false) })
    }

    @Test fun lockCrossesMonthBoundary() {
        val nov1 = LocalDate.of(2026, 11, 1)
        assertTrue(AlarmRules.isLocked(nov1, LocalDateTime.of(2026, 10, 31, 22, 0)))
        assertFalse(AlarmRules.isLocked(nov1, LocalDateTime.of(2026, 10, 31, 21, 59, 59)))
    }

    @Test fun offOverrideBeatsFrozenTimeBeforeLock() {
        // Turned off before the lock: the stale armed time must not win.
        assertEquals(AlarmRules.OFF, AlarmRules.effective(tue, mon.atTime(20, 0), AlarmRules.OFF, "06:30", frozen = "06:30"))
    }

    @Test fun nextAlarmAtExactlyNowIsSkipped() {
        val next = AlarmRules.nextAlarm(mon.atTime(6, 30)) { DayAlarmInputs("06:30", false) }
        assertEquals(tue, next?.date)
    }

    @Test fun scanningBeforeTheAlarmCountsAsOnTime() {
        val early = AlarmRules.morning(mon, tue.atTime(12, 0), "06:30", "05:50")
        assertEquals(Morning.ON_TIME, early.status)
        assertTrue(early.scored)
    }

    @Test fun todayMovesFromUpcomingToWaitingToMissed() {
        fun at(h: Int, m: Int) = AlarmRules.morning(tue, tue.atTime(h, m), "06:30", null)
        assertEquals(Morning.UPCOMING, at(5, 0).status)
        assertFalse(at(5, 0).scored)
        assertEquals("alarm time itself", Morning.WAITING, at(6, 30).status)
        assertEquals(Morning.WAITING, at(7, 29).status)
        assertFalse(at(7, 29).scored)
        assertEquals("ring window over", Morning.MISSED, at(7, 30).status)
        assertTrue(at(7, 30).scored)
    }

    @Test fun onTimeBoundaryIsInclusive() {
        assertEquals(Morning.ON_TIME, AlarmRules.morning(mon, tue.atTime(12, 0), "06:30", "06:40").status)
        assertEquals(Morning.LATE, AlarmRules.morning(mon, tue.atTime(12, 0), "06:30", "06:41").status)
    }

    @Test fun morningScoring() {
        assertEquals(Morning.ON_TIME, AlarmRules.morning(mon, tue.atTime(12, 0), "06:30", "06:40").status)
        val late = AlarmRules.morning(mon, tue.atTime(12, 0), "06:30", "06:52")
        assertEquals(Morning.LATE, late.status)
        assertEquals(22, late.minutesLate)
        assertEquals(Morning.MISSED, AlarmRules.morning(mon, tue.atTime(12, 0), "06:30", null).status)
        assertEquals("today, past the ring window", Morning.MISSED, AlarmRules.morning(tue, tue.atTime(12, 0), "06:30", null).status)
        assertEquals(Morning.PLANNED, AlarmRules.morning(tue.plusDays(1), tue.atTime(12, 0), "06:30", null).status)
        val off = AlarmRules.morning(mon, tue.atTime(12, 0), AlarmRules.OFF, null)
        assertEquals(Morning.OFF, off.status)
        assertFalse(off.scored)
    }
}
