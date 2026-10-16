package app.daybook.checkin

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.daybook.MainActivity
import app.daybook.R
import app.daybook.container
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A 9pm notification asking for the day's mood and energy. Skipped if both are already rated,
 * and withdrawn as soon as they are. Tapping it opens that day, even if it's opened after midnight.
 */
object CheckInReminder {
    const val HOUR = 21
    const val EXTRA_DATE = "checkin_date"
    private const val CHANNEL = "checkin"
    private const val NOTIFICATION_ID = 21

    @SuppressLint("MissingPermission") // USE_EXACT_ALARM is granted at install
    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(HOUR, 0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, CheckInReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val alarms = context.getSystemService(AlarmManager::class.java)
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }

    fun show(context: Context, date: LocalDate) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Evening check-in", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_DATE, date.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_day)
                .setContentTitle("How was today?")
                .setContentText("Rate your mood and energy")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val c = context.container
        c.appScope.launch {
            try {
                val today = LocalDate.now()
                if (!c.days.isRated(today)) CheckInReminder.show(context, today)
                CheckInReminder.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
