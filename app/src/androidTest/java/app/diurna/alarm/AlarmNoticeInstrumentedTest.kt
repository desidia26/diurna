package app.diurna.alarm

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.domain.AlarmNotice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full-screen / high-importance alarm notification values the service builds from [AlarmNotice].
 * Kept as a device test so a refactor cannot silently desync the Service constants.
 */
@RunWith(AndroidJUnit4::class)
class AlarmNoticeInstrumentedTest {
    @Test fun serviceStopActionMatchesNotice() {
        assertEquals(AlarmNotice.STOP_ACTION, AlarmService.ACTION_STOP)
    }

    @Test fun notificationIdsMatchWhatServiceUses() {
        assertEquals("alarm", AlarmNotice.CHANNEL_ID)
        assertEquals(1, AlarmNotice.NOTIFICATION_ID)
        assertEquals("Time to get up", AlarmNotice.TITLE)
        assertEquals("Scan your porch code to stop the alarm", AlarmNotice.TEXT)
    }
}
