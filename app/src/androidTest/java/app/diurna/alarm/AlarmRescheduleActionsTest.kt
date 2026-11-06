package app.diurna.alarm

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.domain.AlarmReschedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** BootReceiver filters on string constants; they must stay identical to platform Intent actions. */
@RunWith(AndroidJUnit4::class)
class AlarmRescheduleActionsTest {
    @Test fun constantsMatchPlatformIntentActions() {
        assertEquals(Intent.ACTION_BOOT_COMPLETED, AlarmReschedule.ACTION_BOOT_COMPLETED)
        assertEquals(Intent.ACTION_TIME_CHANGED, AlarmReschedule.ACTION_TIME_CHANGED)
        assertEquals(Intent.ACTION_TIMEZONE_CHANGED, AlarmReschedule.ACTION_TIMEZONE_CHANGED)
        assertEquals(Intent.ACTION_MY_PACKAGE_REPLACED, AlarmReschedule.ACTION_MY_PACKAGE_REPLACED)
        assertTrue(AlarmReschedule.shouldReschedule(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(AlarmReschedule.shouldReschedule(Intent.ACTION_TIMEZONE_CHANGED))
    }
}
