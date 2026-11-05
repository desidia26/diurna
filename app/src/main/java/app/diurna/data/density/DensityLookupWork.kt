package app.diurna.data.density

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.diurna.container
import app.diurna.data.MealEntity
import app.diurna.domain.DensityUnitKind
import app.diurna.domain.IngredientUnit
import kotlinx.coroutines.CancellationException

/**
 * Background fill of density / grams-per-each on recipe lines that still lack grams.
 * Idempotent: skips lines that already have grams. Manual density_id is never overwritten
 * by [IngredientDensityResolver.applyToLine].
 */
class DensityLookupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val mealId = inputData.getLong(KEY_MEAL_ID, 0L)
        val resolver = c.densityResolver
        val db = c.db
        return try {
            val mealIds: List<Long> = if (mealId > 0L) {
                listOf(mealId)
            } else {
                db.recipeIngredientDao().blockedNeedingDensity()
                    .map { it.mealId }.distinct()
            }
            var filled = 0
            for (id in mealIds) {
                if (isStopped) break
                val lines = db.recipeIngredientDao().forMeal(id)
                if (lines.none { it.needsDensityLookup() }) continue
                var changed = false
                val updated = lines.map { line ->
                    if (!line.needsDensityLookup()) return@map line
                    val kind = DensityUnitKind.of(IngredientUnit.of(line.unit)) ?: return@map line
                    val lookup = resolver.resolve(line.name, kind) ?: return@map line
                    val next = resolver.applyToLine(line, lookup)
                    if (next.grams != null && line.grams == null) {
                        filled++
                        changed = true
                    }
                    next
                }
                if (!changed) continue
                val meal = db.mealDao().get(id) ?: continue
                if (meal.kind != MealEntity.KIND_RECIPE) continue
                if (updated.any { it.needsDensityLookup() }) {
                    db.recipeIngredientDao().deleteForMeal(id)
                    db.recipeIngredientDao().insertAll(
                        updated.mapIndexed { i, line -> line.copy(id = 0, mealId = id, position = i) },
                    )
                } else {
                    c.library.saveRecipe(meal, updated.map { it.copy(id = 0) })
                }
            }
            Result.success(workDataOf(KEY_FILLED to filled))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE = "ingredient-density-lookup"
        const val KEY_MEAL_ID = "mealId"
        const val KEY_FILLED = "filled"

        fun enqueue(context: Context, mealId: Long = 0L) {
            val request = OneTimeWorkRequestBuilder<DensityLookupWorker>()
                .setInputData(workDataOf(KEY_MEAL_ID to mealId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    if (mealId > 0L) "$UNIQUE-$mealId" else UNIQUE,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
        }
    }
}
