package app.diurna.ai

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.diurna.R
import app.diurna.container
import app.diurna.data.AppDatabase
import app.diurna.data.FoodEntryEntity
import app.diurna.data.fdc.AminoResolver
import app.diurna.data.fdc.IngredientsJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Backfills sub-macro / amino detail on past estimated entries. WorkManager-free runner for tests;
 * [DetailBackfillWorker] wraps it. Sequential, idempotent (already-filled skipped), cancelable.
 */
class DetailBackfillRunner(
    private val db: AppDatabase,
    private val estimator: FoodEstimator,
    private val photo: (String) -> ByteArray? = { null },
    private val fdcKeyPresent: suspend () -> Boolean = { false },
    private val aminoResolver: AminoResolver? = null,
    private val onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    private val isCancelled: () -> Boolean = { false },
) {
    suspend fun preview(fromKey: String?, toKey: String?): DetailBackfill.Preview {
        val entries = db.foodDao().estimatedInRange(fromKey, toKey)
        val wantAminos = fdcKeyPresent() && aminoResolver != null
        var ai = 0
        var meal = 0
        for (e in entries) {
            when {
                DetailBackfill.needsAiFill(e, wantAminos) -> ai++
                DetailBackfill.needsMealAttempt(e) -> meal++
            }
        }
        return DetailBackfill.Preview(entryCount = ai + meal, aiCalls = ai, mealCount = meal)
    }

    suspend fun run(fromKey: String?, toKey: String?): DetailBackfill.Summary {
        val food = db.foodDao()
        val candidates = food.estimatedInRange(fromKey, toKey)
        val wantAminos = fdcKeyPresent() && aminoResolver != null
        val work = candidates.filter {
            DetailBackfill.needsAiFill(it, wantAminos) || DetailBackfill.needsMealAttempt(it)
        }
        var filled = 0
        var partial = 0
        var needsMeal = 0
        var failed = 0
        var skipped = 0
        val total = work.size
        onProgress(0, total)
        for ((index, seed) in work.withIndex()) {
            if (isCancelled()) break
            // Reload so a concurrent edit / prior fill is respected (idempotent).
            val entry = food.get(seed.id) ?: run {
                skipped++
                onProgress(index + 1, total)
                continue
            }
            if (entry.estimatedAt == null) {
                skipped++
                onProgress(index + 1, total)
                continue
            }
            try {
                when (entry.source) {
                    FoodEntryEntity.SOURCE_MEAL -> {
                        when (val r = fillMeal(entry)) {
                            MealResult.FILLED -> {
                                filled++
                                val after = food.get(entry.id)
                                if (after?.aminoPartial == true) partial++
                            }
                            MealResult.NEEDS_DETAIL -> needsMeal++
                            MealResult.SKIPPED -> skipped++
                        }
                    }
                    else -> {
                        if (!DetailBackfill.needsAiFill(entry, wantAminos)) {
                            skipped++
                        } else {
                            val ok = fillAi(entry, wantAminos)
                            if (ok) {
                                filled++
                                val after = food.get(entry.id)
                                if (after?.aminoPartial == true) partial++
                            } else {
                                failed++
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
            }
            onProgress(index + 1, total)
        }
        return DetailBackfill.Summary(filled, partial, needsMeal, failed, skipped)
    }

    private enum class MealResult { FILLED, NEEDS_DETAIL, SKIPPED }

    private suspend fun fillMeal(entry: FoodEntryEntity): MealResult {
        if (!DetailBackfill.needsMealAttempt(entry)) return MealResult.SKIPPED
        val mealId = entry.mealId ?: return MealResult.NEEDS_DETAIL
        val meal = db.mealDao().get(mealId) ?: return MealResult.NEEDS_DETAIL
        if (!DetailBackfill.mealHasDetail(meal)) return MealResult.NEEDS_DETAIL
        val current = DetailBackfill.fromEntry(entry)
        val incoming = DetailBackfill.fromMeal(meal, entry.servings)
        val merged = DetailBackfill.mergeNullsOnly(current, incoming, entry.carbsG, entry.fatG)
        if (merged == current) return MealResult.SKIPPED
        writeDetail(entry, merged)
        return MealResult.FILLED
    }

    /**
     * Re-runs the detailed estimate; only writes when the whole pipeline succeeds.
     * Retries transient 429/5xx a few times with backoff; then counts as failed and leaves entry unchanged.
     */
    private suspend fun fillAi(entry: FoodEntryEntity, wantAminos: Boolean): Boolean {
        var attempt = 0
        while (true) {
            if (isCancelled()) return false
            try {
                val jpeg = entry.photo?.let { name -> withContext(Dispatchers.IO) { photo(name) } }
                val e = estimator.estimate(
                    entry.text, jpeg, detailed = true, ingredients = wantAminos,
                )
                var aminos = null as app.diurna.domain.AminoAcids?
                var ingredientsJson: String? = null
                var aminoMatched: Int? = null
                var aminoTotal: Int? = null
                if (wantAminos && !e.ingredients.isNullOrEmpty() && aminoResolver != null) {
                    val resolved = aminoResolver.resolve(e.ingredients)
                    aminos = resolved.aminos
                    ingredientsJson = IngredientsJson.encode(resolved.ingredients)
                    aminoMatched = resolved.matchedCount
                    aminoTotal = resolved.totalCount
                }
                // Re-check still needs fill and macros unchanged before write.
                val fresh = db.foodDao().get(entry.id) ?: return false
                if (fresh.estimatedAt == null) return false
                if (fresh.text != entry.text || fresh.photo != entry.photo) return false
                if (DetailBackfill.macrosOf(fresh) != DetailBackfill.macrosOf(entry)) return false
                if (!DetailBackfill.needsAiFill(fresh, wantAminos)) return true // already filled concurrently
                val current = DetailBackfill.fromEntry(fresh)
                val incoming = DetailBackfill.fromEstimate(e, aminos, ingredientsJson, aminoMatched, aminoTotal)
                val merged = DetailBackfill.mergeNullsOnly(current, incoming, fresh.carbsG, fresh.fatG)
                writeDetail(fresh, merged)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (_: NoApiKey) {
                return false
            } catch (_: EstimateFailed) {
                return false
            } catch (e: Exception) {
                val retryable = e is EstimateRetryable || e is IOException
                if (!retryable || attempt >= MAX_ENTRY_RETRIES - 1) return false
                attempt++
                delay(RETRY_BASE_MS * (1L shl (attempt - 1).coerceAtMost(4)))
            }
        }
    }

    private suspend fun writeDetail(entry: FoodEntryEntity, d: DetailBackfill.Detail) {
        db.foodDao().fillDetailNulls(
            entry.id,
            d.fiberG, d.solubleFiberG, d.insolubleFiberG,
            d.sugarsG, d.addedSugarsG, d.starchG,
            d.satFatG, d.monoFatG, d.polyFatG, d.transFatG,
            d.cholesterolMg, d.omega3G, d.omega6G, d.omega9G,
            d.hisMg, d.ileMg, d.leuMg, d.lysMg, d.metMg,
            d.pheMg, d.thrMg, d.trpMg, d.valMg, d.cysMg, d.tyrMg,
            d.ingredientsJson, d.aminoMatched, d.aminoTotal,
            // Guard: macros and estimate identity must still match.
            entry.kcalLow, entry.kcalHigh, entry.proteinG, entry.carbsG, entry.fatG,
            entry.estimateNote, entry.estimateModel, entry.estimatedAt,
        )
    }

    companion object {
        const val MAX_ENTRY_RETRIES = 5
        const val RETRY_BASE_MS = 2_000L
    }
}

class DetailBackfillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val from = inputData.getString(KEY_FROM)?.ifBlank { null }
        val to = inputData.getString(KEY_TO)?.ifBlank { null }
        val c = applicationContext.container
        notifyProgress(0, 0)
        val runner = DetailBackfillRunner(
            db = c.db,
            estimator = SelectedFoodEstimator({ c.aiProvider.get() }, mapOf(
                AiProvider.ANTHROPIC to ClaudeFoodEstimator({ c.apiKeys.getValue(AiProvider.ANTHROPIC).get() }),
                AiProvider.XAI to GrokFoodEstimator({ c.apiKeys.getValue(AiProvider.XAI).get() }),
            )),
            photo = c.foodPhotos::read,
            fdcKeyPresent = { c.usdaKey.get()?.isNotBlank() == true },
            aminoResolver = c.aminoResolver,
            onProgress = { done, total ->
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                notifyProgress(done, total)
            },
            isCancelled = { isStopped },
        )
        return try {
            val summary = runner.run(from, to)
            val out = workDataOf(
                KEY_FILLED to summary.filled,
                KEY_PARTIAL to summary.partialAminos,
                KEY_NEEDS_MEAL to summary.needsMealDetail,
                KEY_FAILED to summary.failed,
                KEY_SKIPPED to summary.skipped,
            )
            cancelProgressNotification()
            Result.success(out)
        } catch (e: CancellationException) {
            cancelProgressNotification()
            throw e
        } catch (_: Exception) {
            cancelProgressNotification()
            Result.retry()
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyProgress(done: Int, total: Int) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Detail backfill", NotificationManager.IMPORTANCE_LOW),
        )
        val text = if (total <= 0) "Starting…" else "$done of $total"
        nm.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setContentTitle("Filling nutrition detail")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_day)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(total.coerceAtLeast(0), done.coerceAtLeast(0), total <= 0)
                .build(),
        )
    }

    private fun cancelProgressNotification() {
        applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    companion object {
        const val UNIQUE = "detail-backfill"
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FILLED = "filled"
        const val KEY_PARTIAL = "partial"
        const val KEY_NEEDS_MEAL = "needsMeal"
        const val KEY_FAILED = "failed"
        const val KEY_SKIPPED = "skipped"
        private const val CHANNEL = "detail_backfill"
        private const val NOTIFICATION_ID = 42

        fun enqueue(context: Context, fromKey: String?, toKey: String?) {
            val request = OneTimeWorkRequestBuilder<DetailBackfillWorker>()
                .setInputData(workDataOf(
                    KEY_FROM to (fromKey ?: ""),
                    KEY_TO to (toKey ?: ""),
                ))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }

        fun observe(context: Context): Flow<BackfillWorkState> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos ->
                val info = infos.firstOrNull()
                when {
                    info == null -> BackfillWorkState.Idle
                    info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED ->
                        BackfillWorkState.Running(
                            done = info.progress.getInt(KEY_DONE, 0),
                            total = info.progress.getInt(KEY_TOTAL, 0),
                        )
                    info.state == WorkInfo.State.SUCCEEDED -> BackfillWorkState.Finished(
                        DetailBackfill.Summary(
                            filled = info.outputData.getInt(KEY_FILLED, 0),
                            partialAminos = info.outputData.getInt(KEY_PARTIAL, 0),
                            needsMealDetail = info.outputData.getInt(KEY_NEEDS_MEAL, 0),
                            failed = info.outputData.getInt(KEY_FAILED, 0),
                            skipped = info.outputData.getInt(KEY_SKIPPED, 0),
                        ),
                    )
                    info.state == WorkInfo.State.CANCELLED -> BackfillWorkState.Cancelled
                    info.state == WorkInfo.State.FAILED -> BackfillWorkState.Failed
                    else -> BackfillWorkState.Idle
                }
            }
    }
}

sealed class BackfillWorkState {
    data object Idle : BackfillWorkState()
    data class Running(val done: Int, val total: Int) : BackfillWorkState()
    data class Finished(val summary: DetailBackfill.Summary) : BackfillWorkState()
    data object Cancelled : BackfillWorkState()
    data object Failed : BackfillWorkState()
}
