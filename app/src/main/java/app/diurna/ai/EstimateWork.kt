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
import app.diurna.data.OffSnapshot
import app.diurna.data.OpenFoodFacts
import app.diurna.data.ProductSource
import app.diurna.data.parseOffProduct
import app.diurna.data.parseOffSnapshot
import app.diurna.data.servingGrams
import app.diurna.data.toMacros
import app.diurna.data.fdc.AminoResolver
import app.diurna.data.fdc.IngredientsJson
import app.diurna.domain.AminoAcids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Works through every food entry waiting for an estimate. Kept free of WorkManager so tests can
 * drive it with a fake estimator.
 *
 * [photo] reads an entry's photo by name. It's read fresh on every attempt (retries included) rather
 * than carried in the work request; a photo that has gone missing just means a text-only estimate.
 *
 * When Detailed nutrition is on and an FDC key is available, the model returns an ingredient list
 * and [aminoResolver] looks up amino milligrams from USDA FoodData Central (never AI-guessed).
 */
class EstimateRunner(
    private val db: AppDatabase,
    private val estimator: FoodEstimator,
    private val photo: (String) -> ByteArray? = { null },
    /** When true, the model is asked for fiber / sugars / lipid breakdown (Detailed nutrition). */
    private val detailed: suspend () -> Boolean = { false },
    /** True when a USDA FDC API key is set (gates ingredient list + amino lookup). */
    private val fdcKeyPresent: suspend () -> Boolean = { false },
    private val aminoResolver: AminoResolver? = null,
    /** Open Food Facts (or fake in tests). Used when a pending entry has a barcode. */
    private val products: ProductSource = OpenFoodFacts(),
) {
    enum class Outcome { DONE, RETRY }

    /** [giveUp]: this is the last allowed attempt, so temporary trouble becomes a visible failure. */
    suspend fun runPending(giveUp: Boolean = false): Outcome {
        val food = db.foodDao()
        while (true) {
            val batch = food.pendingEstimates()
            if (batch.isEmpty()) return Outcome.DONE
            for (entry in batch) {
                try {
                    // Prefer OFF over AI when the entry carries a barcode (fill / explicit re-estimate).
                    if (!entry.barcode.isNullOrBlank() && tryEstimateFromOff(entry)) continue
                    val jpeg = entry.photo?.let { name -> withContext(Dispatchers.IO) { photo(name) } }
                    val wantDetail = detailed()
                    val wantIngredients = wantDetail && fdcKeyPresent() && aminoResolver != null
                    val e = estimator.estimate(
                        entry.text, jpeg, detailed = wantDetail, ingredients = wantIngredients,
                    )
                    var aminos: AminoAcids? = null
                    var ingredientsJson: String? = null
                    var aminoMatched: Int? = null
                    var aminoTotal: Int? = null
                    if (wantIngredients && !e.ingredients.isNullOrEmpty()) {
                        val resolved = aminoResolver!!.resolve(e.ingredients)
                        aminos = resolved.aminos
                        ingredientsJson = IngredientsJson.encode(resolved.ingredients)
                        aminoMatched = resolved.matchedCount
                        aminoTotal = resolved.totalCount
                    }
                    // Guarded on the text and photo: if either was edited mid-call this no-ops, and
                    // the edit has already queued the entry for the next pass.
                    food.setEstimate(
                        entry.id, entry.text, entry.photo, e.kcalLow, e.kcalHigh, e.proteinG, e.carbsG, e.fatG,
                        e.fiberG, e.solubleFiberG, e.insolubleFiberG,
                        e.sugarsG, e.addedSugarsG, e.starchG,
                        e.satFatG, e.monoFatG, e.polyFatG, e.transFatG,
                        e.cholesterolMg, e.omega3G, e.omega6G, e.omega9G,
                        aminos?.hisMg, aminos?.ileMg, aminos?.leuMg, aminos?.lysMg, aminos?.metMg,
                        aminos?.pheMg, aminos?.thrMg, aminos?.trpMg, aminos?.valMg, aminos?.cysMg, aminos?.tyrMg,
                        ingredientsJson, aminoMatched, aminoTotal,
                        e.note, e.model ?: estimator.model, System.currentTimeMillis(),
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

    /**
     * Uses a stored OFF snapshot (retrying the lookup when it previously failed) to fill a pending
     * estimate. Returns true when the entry was written (caller should skip AI).
     */
    private suspend fun tryEstimateFromOff(entry: app.diurna.data.FoodEntryEntity): Boolean {
        val food = db.foodDao()
        val code = entry.barcode ?: return false
        var snap = OffSnapshot.decode(entry.offSnapshotJson)
        if (snap == null || !snap.lookupOk || snap.per100Json == null) {
            val json = try {
                products.fetch(code)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val at = System.currentTimeMillis()
            snap = json?.let { parseOffSnapshot(it, at) } ?: OffSnapshot.failed(at)
            food.setBarcodeSnapshot(entry.id, code, snap.encode())
            // Also refresh meal snapshot when linked.
            entry.mealId?.let { mid ->
                db.mealDao().get(mid)?.let { meal ->
                    if (meal.barcode == code) {
                        db.mealDao().update(meal.copy(offSnapshotJson = snap.encode()))
                    }
                }
            }
        }
        if (!snap.lookupOk) return false
        val fromRaw = snap.rawJson?.let { parseOffProduct(it) }
        val grams = snap.servingGrams()
        val macros = fromRaw?.let {
            app.diurna.data.OffMacros(it.kcal, it.proteinG, it.carbsG, it.fatG)
        } ?: snap.toMacros(grams) ?: return false
        val detail = if (fromRaw != null) {
            DetailBackfill.fromMeal(fromRaw, 1.0)
        } else {
            DetailBackfill.fromOffSnapshot(snap, grams) ?: DetailBackfill.Detail()
        }
        val note = fromRaw?.note ?: snap.servingSize ?: "Open Food Facts"
        food.setEstimate(
            entry.id, entry.text, entry.photo,
            macros.kcal, macros.kcal, macros.proteinG, macros.carbsG, macros.fatG,
            detail.fiberG, detail.solubleFiberG, detail.insolubleFiberG,
            detail.sugarsG, detail.addedSugarsG, detail.starchG,
            detail.satFatG, detail.monoFatG, detail.polyFatG, detail.transFatG,
            detail.cholesterolMg, detail.omega3G, detail.omega6G, detail.omega9G,
            null, null, null, null, null, null, null, null, null, null, null,
            null, null, null,
            note, "open-food-facts", System.currentTimeMillis(),
        )
        return true
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
