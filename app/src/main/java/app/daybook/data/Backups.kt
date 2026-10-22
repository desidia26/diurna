package app.daybook.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.daybook.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Diurna backs itself up to its folder in shared storage
 * (/sdcard/Android/data/app.daybook/files/backups), which a computer can pull over adb even from
 * a release build. Each backup is a consistent copy of the database plus the settings file. The
 * API key is left out on purpose: it's encrypted with a key that never leaves this phone.
 */
class Backups(private val context: Context, private val db: AppDatabase) {
    private val mutex = Mutex()
    private val _last = MutableStateFlow(latest()?.let { stampOf(it) })

    /** When the newest backup was taken, for the Setup card. */
    val last: StateFlow<LocalDateTime?> = _last

    val dir: File get() = File(context.getExternalFilesDir(null), "backups")

    suspend fun backupNow(): File? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val now = LocalDateTime.now()
                val stamp = now.format(STAMP)
                val dbFile = File(dir, "daybook-$stamp.db")
                // VACUUM INTO writes a complete, consistent copy, including recent writes still in the
                // WAL. SQLite creates it owner-only, which adb can't read in shared storage, so it's
                // written to private scratch space and then copied over, picking up normal permissions.
                val scratch = File(context.cacheDir, "backup-$stamp.db").apply { delete() }
                db.openHelper.writableDatabase.execSQL("VACUUM INTO '${scratch.absolutePath}'")
                scratch.copyTo(dbFile, overwrite = true)
                scratch.delete()
                File(context.filesDir, "datastore/settings.preferences_pb").takeIf { it.exists() }
                    ?.copyTo(File(dir, "settings-$stamp.preferences_pb"), overwrite = true)
                prune()
                _last.value = now
                Log.d(TAG, "backup written: ${dbFile.name}")
                dbFile
            }.onFailure { Log.w(TAG, "backup failed", it) }.getOrNull()
        }
    }

    private fun latest(): File? =
        dir.listFiles { f -> f.name.startsWith("daybook-") && f.name.endsWith(".db") }?.maxByOrNull { it.name }

    private fun stampOf(f: File): LocalDateTime? =
        runCatching { LocalDateTime.parse(f.name.removePrefix("daybook-").removeSuffix(".db"), STAMP) }.getOrNull()

    /** Keeps the newest [KEEP] backups (database and settings together). */
    private fun prune() {
        val stamps = dir.listFiles { f -> f.name.startsWith("daybook-") && f.name.endsWith(".db") }
            ?.map { it.name.removePrefix("daybook-").removeSuffix(".db") }?.sortedDescending() ?: return
        stamps.drop(KEEP).forEach { s ->
            File(dir, "daybook-$s.db").delete()
            File(dir, "settings-$s.preferences_pb").delete()
        }
    }

    /** Once a day in the background. */
    fun scheduleDaily() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    companion object {
        private const val TAG = "DiurnaBackup"
        private const val WORK = "daily-backup"
        private const val KEEP = 14
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Extra on MainActivity's launch intent that asks for a backup (used by scripts/backup-db). */
        const val EXTRA_BACKUP = "backup"
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (applicationContext.container.backups.backupNow() != null) Result.success() else Result.retry()
}
