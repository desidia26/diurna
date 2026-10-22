package app.diurna

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/**
 * The code moved from package app.daybook to app.diurna (the app id stayed app.daybook). Alarms and
 * background work saved before that point name classes that no longer exist, so the first launch
 * after the update cancels them; normal startup then schedules everything again under the new
 * names. Runs once, before anything is scheduled.
 */
object PackageRename {
    private const val PREFS = "package_rename"
    private const val DONE = "daybook_to_diurna_done"
    private const val OLD = "app.daybook"

    /** Receivers that had alarms set under their old names, with the request codes used. */
    private val oldAlarms = listOf(
        "$OLD.alarm.AlarmReceiver" to 1, // real alarm
        "$OLD.alarm.AlarmReceiver" to 2, // test alarm
        "$OLD.wallpaper.LockWallpaperTickReceiver" to 0,
        "$OLD.steps.StepMidnightReceiver" to 0,
        "$OLD.checkin.CheckInReceiver" to 0,
    )

    /** Unique work names; their saved requests point at the old worker classes. */
    private val oldWork = listOf("step-sample", "daily-backup", "lock-wallpaper-steps", "estimate-food")

    fun cleanUpOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE, false)) return

        val alarms = context.getSystemService(AlarmManager::class.java)
        for ((receiver, request) in oldAlarms) {
            val intent = Intent().setComponent(ComponentName(context.packageName, receiver))
            PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let { alarms.cancel(it); it.cancel() }
        }
        val work = WorkManager.getInstance(context)
        // Wait, so nothing scheduled afterwards is mistaken for the old work and kept.
        oldWork.forEach { work.cancelUniqueWork(it).result.get() }
        work.pruneWork().result.get()

        prefs.edit().putBoolean(DONE, true).apply()
    }
}
