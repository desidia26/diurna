package app.diurna.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.diurna.container
import app.diurna.data.AppDatabase
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Works through every food entry waiting for an estimate. Kept free of WorkManager so tests can
 * drive it with a fake estimator.
 */
class EstimateRunner(private val db: AppDatabase, private val estimator: FoodEstimator) {
    enum class Outcome { DONE, RETRY }

    /** [giveUp]: this is the last allowed attempt, so temporary trouble becomes a visible failure. */
    suspend fun runPending(giveUp: Boolean = false): Outcome {
        val food = db.foodDao()
        while (true) {
            val batch = food.pendingEstimates()
            if (batch.isEmpty()) return Outcome.DONE
            for (entry in batch) {
                try {
                    val e = estimator.estimate(entry.text)
                    // Guarded on the text: if it was edited mid-call this no-ops, and the edit has
                    // already queued the new text for the next pass.
                    food.setEstimate(
                        entry.id, entry.text, e.kcalLow, e.kcalHigh, e.proteinG, e.carbsG, e.fatG, e.note,
                        e.model ?: estimator.model, System.currentTimeMillis(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NoApiKey) {
                    food.setEstimateError(entry.id, e.message ?: "Add an Anthropic API key in Setup")
                } catch (e: EstimateFailed) {
                    food.setEstimateError(entry.id, e.message ?: "Couldn't estimate this entry. Tap Retry.")
                } catch (e: Exception) {
                    if (e !is EstimateRetryable && e !is IOException) {
                        // Not a known temporary problem: show it instead of retrying forever.
                        food.setEstimateError(entry.id, "Unexpected error (${e.javaClass.simpleName}). Tap Retry.")
                        continue
                    }
                    // Rate limited or a server hiccup: leave it pending and try later, up to a point.
                    if (!giveUp) return Outcome.RETRY
                    food.failAllPending(estimator.gaveUpMessage)
                    return Outcome.DONE
                }
            }
        }
    }
}

class EstimateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (applicationContext.container.estimates.runPending(giveUp = runAttemptCount >= MAX_ATTEMPTS - 1)) {
            EstimateRunner.Outcome.DONE -> Result.success()
            EstimateRunner.Outcome.RETRY -> Result.retry()
        }

    companion object {
        /**
         * With 30s doubling backoff, 8 attempts span about two hours. Attempts only start while
         * online, so time spent offline doesn't use them up.
         */
        const val MAX_ATTEMPTS = 8

        /** Queues a run once the phone is online; extra calls while one is queued fold into it. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<EstimateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("estimate-food", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
