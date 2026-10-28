package app.diurna.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.diurna.container
import app.diurna.checkin.CheckInReminder
import app.diurna.steps.StepSampling
import kotlinx.coroutines.launch

/** Fired by AlarmManager: start ringing, then arm the following alarm. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = Intent(context, AlarmService::class.java)
            .putExtra(AlarmService.EXTRA_DATE, intent.getStringExtra(AlarmService.EXTRA_DATE))
            .putExtra(AlarmService.EXTRA_TEST, intent.getBooleanExtra(AlarmService.EXTRA_TEST, false))
        ContextCompat.startForegroundService(context, service)
        rescheduleAsync(context)
    }
}

/** Alarms don't survive reboots, and clock changes shift them, so re-arm on both. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the system broadcasts below; ignore anything else another app might send.
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> rescheduleAsync(context, alsoWallpaper = true)
        }
    }
}

private fun BroadcastReceiver.rescheduleAsync(context: Context, alsoWallpaper: Boolean = false) {
    val pending = goAsync()
    val c = context.container
    c.appScope.launch {
        try {
            c.finalizer.catchUp()
            c.scheduler.reschedule()
            // The wallpaper's 10pm/midnight ticks are alarms too, and the date may have changed.
            if (alsoWallpaper) {
                // A reboot resets the step sensor; look now so the new baseline is caught early.
                c.stepCounter.sample()
                StepSampling.scheduleBeforeMidnight(context)
                CheckInReminder.schedule(context)
                c.lockWallpaper.refresh()
            }
        } finally {
            pending.finish()
        }
    }
}
