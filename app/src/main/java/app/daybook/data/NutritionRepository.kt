package app.daybook.data

import app.daybook.domain.NutritionTotals
import app.daybook.domain.Profile
import app.daybook.domain.TargetMath
import app.daybook.domain.Targets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Body weight and daily targets. A finalized day uses its snapshot; any other day computes from
 * the current profile and the latest weigh-in on or before it.
 */
class NutritionRepository(
    private val db: AppDatabase,
    private val profile: Flow<Profile?>,
) {
    private val days = db.dayDao()

    suspend fun setBodyWeight(date: LocalDate, lb: Double?) {
        days.insertIgnore(DayEntity(date.key()))
        days.setBodyWeight(date.key(), lb)
    }

    /** Live targets, ignoring any snapshot. Used when finalizing a day. */
    suspend fun computeTargets(date: LocalDate): Targets? {
        val p = profile.first() ?: return null
        val weight = days.latestWeight(date.key()) ?: return null
        return TargetMath.targets(p, weight, date)
    }

    fun observeTargets(date: LocalDate): Flow<Targets?> =
        combine(days.observe(date.key()), profile, days.observeLatestWeight(date.key())) { day, p, weight ->
            day?.snapshotTargets() ?: if (p != null && weight != null) TargetMath.targets(p, weight, date) else null
        }
}

fun List<FoodEntryEntity>.totals(): NutritionTotals {
    val done = filter { it.estimatedAt != null }
    return NutritionTotals(
        kcal = done.sumOf { it.kcalMid ?: 0 },
        proteinG = done.sumOf { it.proteinG ?: 0 },
        carbsG = done.sumOf { it.carbsG ?: 0 },
        fatG = done.sumOf { it.fatG ?: 0 },
        estimated = done.size,
        pending = count { it.estimatedAt == null && it.estimateError == null },
        failed = count { it.estimateError != null },
    )
}

fun DayEntity.snapshotTargets(): Targets? =
    if (finalized && targetKcal != null) Targets(targetKcal, targetProteinG ?: 0, targetCarbsG ?: 0, targetFatG ?: 0) else null
