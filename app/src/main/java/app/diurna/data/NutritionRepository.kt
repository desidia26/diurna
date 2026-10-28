package app.diurna.data

import app.diurna.domain.NutritionTotals
import app.diurna.domain.Profile
import app.diurna.domain.TargetMath
import app.diurna.domain.Targets
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
    /** Sum known values; null when every entry left the field unknown (distinct from a real 0). */
    fun sumOpt(pick: (FoodEntryEntity) -> Int?): Int? {
        var any = false
        var sum = 0
        for (e in done) {
            val v = pick(e) ?: continue
            any = true
            sum += v
        }
        return if (any) sum else null
    }
    return NutritionTotals(
        kcal = done.sumOf { it.kcalMid ?: 0 },
        proteinG = done.sumOf { it.proteinG ?: 0 },
        carbsG = done.sumOf { it.carbsG ?: 0 },
        fatG = done.sumOf { it.fatG ?: 0 },
        fiberG = sumOpt { it.fiberG },
        solubleFiberG = sumOpt { it.solubleFiberG },
        insolubleFiberG = sumOpt { it.insolubleFiberG },
        sugarsG = sumOpt { it.sugarsG },
        addedSugarsG = sumOpt { it.addedSugarsG },
        starchG = sumOpt { it.starchG },
        satFatG = sumOpt { it.satFatG },
        monoFatG = sumOpt { it.monoFatG },
        polyFatG = sumOpt { it.polyFatG },
        transFatG = sumOpt { it.transFatG },
        cholesterolMg = sumOpt { it.cholesterolMg },
        omega3G = sumOpt { it.omega3G },
        omega6G = sumOpt { it.omega6G },
        omega9G = sumOpt { it.omega9G },
        estimated = done.size,
        pending = count { it.estimatedAt == null && it.estimateError == null },
        failed = count { it.estimateError != null },
    )
}

fun DayEntity.snapshotTargets(): Targets? =
    if (finalized && targetKcal != null) Targets(targetKcal, targetProteinG ?: 0, targetCarbsG ?: 0, targetFatG ?: 0) else null
