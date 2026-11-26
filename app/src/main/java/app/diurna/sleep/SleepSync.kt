package app.diurna.sleep

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.diurna.AppContainer
import app.diurna.container
import app.diurna.data.health.HealthConnectMissing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * When sleep is read: when the app opens and hourly in the background, while sleep is on in Setup.
 * A failed read only updates the status Setup shows; it never crashes or retries in a loop.
 */
object SleepSync {
    private const val WORK = "sleep-sync"
    private const val TAG = "SleepSync"
    /** Opening the app reads at most this often; Setup's Read now always reads. */
    private const val MIN_GAP_MS = 10 * 60 * 1000L

    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(WORK); return }
        wm.enqueueUniquePeriodicWork(
            WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SleepSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    /** From app open: reads in the background unless sleep is off or a read just ran. */
    fun onAppOpen(c: AppContainer) {
        c.appScope.launch {
            if (!c.settings.sleep.first()) return@launch
            val last = c.settings.sleepStatus.first()?.at ?: 0L
            if (System.currentTimeMillis() - last < MIN_GAP_MS) return@launch
            run(c)
        }
    }

    /** Reads now and records how it went. Returns the problem, or null when it worked. */
    suspend fun run(c: AppContainer): String? {
        val problem = try {
            c.sleep.sync()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: HealthConnectMissing) {
            "Health Connect isn't on this phone"
        } catch (_: SecurityException) {
            "Diurna isn't allowed to read sleep in Health Connect"
        } catch (e: Exception) {
            Log.w(TAG, "sleep read failed: ${e.javaClass.simpleName}")
            "Couldn't read Health Connect"
        }
        c.settings.setSleepStatus(System.currentTimeMillis(), problem)
        return problem
    }
}

class SleepSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (!c.settings.sleep.first()) return Result.success()
        // Without background access Health Connect refuses; app opens still read.
        if (!runCatching { c.healthConnectSleep.backgroundReads() }.getOrDefault(false)) return Result.success()
        SleepSync.run(c)
        return Result.success()
    }
}
