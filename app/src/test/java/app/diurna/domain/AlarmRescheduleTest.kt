package app.diurna.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRescheduleTest {
    @Test fun bootTimeTimezoneAndReplaceReArm() {
        assertTrue(AlarmReschedule.shouldReschedule(AlarmReschedule.ACTION_BOOT_COMPLETED))
        assertTrue(AlarmReschedule.shouldReschedule(AlarmReschedule.ACTION_TIME_CHANGED))
        assertTrue(AlarmReschedule.shouldReschedule(AlarmReschedule.ACTION_TIMEZONE_CHANGED))
        assertTrue(AlarmReschedule.shouldReschedule(AlarmReschedule.ACTION_MY_PACKAGE_REPLACED))
    }

    @Test fun unrelatedActionsAreIgnored() {
        assertFalse(AlarmReschedule.shouldReschedule(null))
        assertFalse(AlarmReschedule.shouldReschedule(""))
        assertFalse(AlarmReschedule.shouldReschedule("android.intent.action.SCREEN_ON"))
        assertFalse(AlarmReschedule.shouldReschedule("app.diurna.STOP_ALARM"))
        assertFalse(AlarmReschedule.shouldReschedule("android.intent.action.TIME_TICK"))
    }

    @Test fun actionStringsMatchPlatformConstants() {
        // BootReceiver is exported; these must stay identical to Intent.* or re-arm never runs.
        assertTrue(AlarmReschedule.ACTION_BOOT_COMPLETED == "android.intent.action.BOOT_COMPLETED")
        assertTrue(AlarmReschedule.ACTION_TIME_CHANGED == "android.intent.action.TIME_SET")
        assertTrue(AlarmReschedule.ACTION_TIMEZONE_CHANGED == "android.intent.action.TIMEZONE_CHANGED")
        assertTrue(AlarmReschedule.ACTION_MY_PACKAGE_REPLACED == "android.intent.action.MY_PACKAGE_REPLACED")
    }
}
