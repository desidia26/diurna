package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AlarmNoticeTest {
    @Test fun notificationCopyAndIdsAreStable() {
        assertEquals("alarm", AlarmNotice.CHANNEL_ID)
        assertEquals("Alarm", AlarmNotice.CHANNEL_NAME)
        assertEquals(1, AlarmNotice.NOTIFICATION_ID)
        assertEquals("Time to get up", AlarmNotice.TITLE)
        assertEquals("Scan your porch code to stop the alarm", AlarmNotice.TEXT)
        assertEquals("app.diurna.STOP_ALARM", AlarmNotice.STOP_ACTION)
    }

    @Test fun maxRingMatchesAlarmRulesWindow() {
        assertEquals(AlarmRules.RING_MINUTES * 60_000L, AlarmNotice.maxRingMs)
    }

    @Test fun stopActionRecognized() {
        assertTrue(AlarmNotice.isStopAction(AlarmNotice.STOP_ACTION))
        assertFalse(AlarmNotice.isStopAction(null))
        assertFalse(AlarmNotice.isStopAction("app.diurna.OTHER"))
    }

    @Test fun ringDateParsesOrNull() {
        assertEquals(LocalDate.of(2026, 11, 5), AlarmNotice.ringDate("2026-11-05"))
        assertNull(AlarmNotice.ringDate(null))
    }
}
