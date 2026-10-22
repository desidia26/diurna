package app.daybook.steps

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.daybook.container
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * When Diurna looks at the step sensor: every 15 minutes in the background, and once at 23:59 so
 * the day's last steps are counted on that day before midnight. (Also at boot and whenever today's
 * count is shown.)
 */
object StepSampling {
    private const val WORK = "step-sample"

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StepSampleWorker>(15, TimeUnit.MINUTES).build(),
        )
        scheduleBeforeMidnight(context)
    }

    @SuppressLint("MissingPermission") // USE_EXACT_ALARM is granted at install
    fun scheduleBeforeMidnight(context: Context) {
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(23, 59)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, StepMidnightReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alarms = context.getSystemService(AlarmManager::class.java)
        // Wakes the phone once a day for a few milliseconds; without it, steps after the last
        // 15-minute look of the evening would be counted on tomorrow.
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }
}

class StepSampleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.container.stepCounter.sample()
        return Result.success()
    }
}

class StepMidnightReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val c = context.container
        c.appScope.launch {
            try {
                c.stepCounter.sample()
                StepSampling.scheduleBeforeMidnight(context)
            } finally {
                pending.finish()
            }
        }
    }
}
