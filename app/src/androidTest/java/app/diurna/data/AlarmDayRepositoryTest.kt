package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.domain.AlarmRules
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Extra morning/alarm coverage around freeze + next-occurrence inputs the scheduler reads.
 * Complements DayRepositoryTest's alarm section.
 */
@RunWith(AndroidJUnit4::class)
class AlarmDayRepositoryTest {
    private lateinit var t: TestDb
    private val days get() = t.days
    private val mon get() = t.mon
    private val tue get() = mon.plusDays(1)

    @Before fun open() { t = TestDb() }
    @After fun close() { t.close() }

    @Test fun freezeLockedDaysPinsTomorrowAfterTenPm() = runTest {
        t.now = mon.atTime(22, 0)
        days.freezeLockedDays()
        val frozen = t.db.dayDao().get(tue.toString())?.scheduledAlarm
        assertEquals(AppDatabase.DEFAULT_ALARM, frozen)
        // Later routine edit must not move the frozen tomorrow alarm.
        t.routine.setAlarm(tue.dayOfWeek.value, "09:15")
        assertEquals(frozen, days.effectiveAlarm(tue))
    }

    @Test fun alarmInputsFeedNextOccurrence() = runTest {
        // Overrides must be set while each day is still unlocked (before 22:00 the day before).
        t.now = mon.minusDays(1).atTime(12, 0)
        days.setAlarmOverride(mon, "06:00")
        days.setAlarmOverride(tue, AlarmRules.OFF)
        val wed = tue.plusDays(1)
        days.setAlarmOverride(wed, "05:30")
        t.now = mon.atTime(5, 0)
        val inputs = (0L until AlarmRules.LOOKAHEAD_DAYS).associate { i ->
            val d = mon.plusDays(i)
            d to days.alarmInputs(d)
        }
        val next = AlarmRules.nextAlarm(t.now) { inputs.getValue(it) }
        assertEquals(mon, next?.date)
        assertEquals("06:00", next?.time.toString())

        days.recordMorning(mon, "06:01", "scan")
        val afterScan = (0L until AlarmRules.LOOKAHEAD_DAYS).associate { i ->
            val d = mon.plusDays(i)
            d to days.alarmInputs(d)
        }
        val next2 = AlarmRules.nextAlarm(t.now) { afterScan.getValue(it) }
        assertEquals(wed, next2?.date)
        assertEquals("05:30", next2?.time.toString())
    }

    @Test fun clearOverrideAfterUnlockClearsStaleSchedule() = runTest {
        days.setAlarmOverride(tue, "05:00")
        days.markScheduled(tue, "05:00")
        assertTrue(days.setAlarmOverride(tue, null))
        assertEquals(null, t.db.dayDao().get(tue.toString())?.scheduledAlarm)
        assertEquals(AppDatabase.DEFAULT_ALARM, days.effectiveAlarm(tue))
    }

    @Test fun scannedAtMarksInputsSoSchedulerSkipsToday() = runTest {
        t.now = mon.atTime(7, 0)
        assertFalse(days.alarmInputs(mon).scanned)
        days.recordMorning(mon, "06:55", "scan")
        assertTrue(days.alarmInputs(mon).scanned)
        assertEquals("scan", t.db.dayDao().get(mon.toString())?.morningSource)
    }
}
