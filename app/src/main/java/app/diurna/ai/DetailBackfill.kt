package app.diurna.ai

import app.diurna.data.FoodEntryEntity
import app.diurna.data.MealEntity
import app.diurna.domain.AminoAcids
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Fill-nulls-only merge for backfilling sub-macros / aminos on past entries.
 * Never overwrites existing non-null detail fields, and never touches kcal / P / C / F /
 * note / model / estimatedAt.
 */
object DetailBackfill {

    data class Detail(
        val fiberG: Int? = null,
        val solubleFiberG: Int? = null,
        val insolubleFiberG: Int? = null,
        val sugarsG: Int? = null,
        val addedSugarsG: Int? = null,
        val starchG: Int? = null,
        val satFatG: Int? = null,
        val monoFatG: Int? = null,
        val polyFatG: Int? = null,
        val transFatG: Int? = null,
        val cholesterolMg: Int? = null,
        val omega3G: Int? = null,
        val omega6G: Int? = null,
        val omega9G: Int? = null,
        val hisMg: Int? = null,
        val ileMg: Int? = null,
        val leuMg: Int? = null,
        val lysMg: Int? = null,
        val metMg: Int? = null,
        val pheMg: Int? = null,
        val thrMg: Int? = null,
        val trpMg: Int? = null,
        val valMg: Int? = null,
        val cysMg: Int? = null,
        val tyrMg: Int? = null,
        val ingredientsJson: String? = null,
        val aminoMatched: Int? = null,
        val aminoTotal: Int? = null,
    ) {
        val hasAnySubMacro: Boolean
            get() = fiberG != null || solubleFiberG != null || insolubleFiberG != null ||
                sugarsG != null || addedSugarsG != null || starchG != null ||
                satFatG != null || monoFatG != null || polyFatG != null || transFatG != null ||
                cholesterolMg != null || omega3G != null || omega6G != null || omega9G != null

        val hasAnyAmino: Boolean
            get() = hisMg != null || ileMg != null || leuMg != null || lysMg != null ||
                metMg != null || pheMg != null || thrMg != null || trpMg != null ||
                valMg != null || cysMg != null || tyrMg != null ||
                aminoMatched != null || aminoTotal != null || ingredientsJson != null

        val aminoPartial: Boolean
            get() = aminoMatched != null && aminoTotal != null &&
                aminoMatched < aminoTotal && aminoMatched > 0
    }

    data class Preview(
        val entryCount: Int,
        /** AI-sourced entries that still need at least one null detail filled (≈ AI calls). */
        val aiCalls: Int,
        val mealCount: Int,
    )

    data class Summary(
        val filled: Int = 0,
        val partialAminos: Int = 0,
        val needsMealDetail: Int = 0,
        val failed: Int = 0,
        val skipped: Int = 0,
    )

    /** Default last 30 days inclusive; [all] clears the range. */
    data class Range(val from: LocalDate?, val to: LocalDate?) {
        companion object {
            fun lastDays(today: LocalDate, days: Int = 30) =
                Range(today.minusDays((days - 1).toLong()), today)
            fun all() = Range(null, null)
        }
        fun fromKey(): String? = from?.toString()
        fun toKey(): String? = to?.toString()
    }

    fun fromEntry(e: FoodEntryEntity) = Detail(
        e.fiberG, e.solubleFiberG, e.insolubleFiberG,
        e.sugarsG, e.addedSugarsG, e.starchG,
        e.satFatG, e.monoFatG, e.polyFatG, e.transFatG,
        e.cholesterolMg, e.omega3G, e.omega6G, e.omega9G,
        e.hisMg, e.ileMg, e.leuMg, e.lysMg, e.metMg,
        e.pheMg, e.thrMg, e.trpMg, e.valMg, e.cysMg, e.tyrMg,
        e.ingredientsJson, e.aminoMatched, e.aminoTotal,
    )

    fun fromEstimate(
        e: FoodEstimate,
        aminos: AminoAcids?,
        ingredientsJson: String?,
        aminoMatched: Int?,
        aminoTotal: Int?,
    ) = Detail(
        e.fiberG, e.solubleFiberG, e.insolubleFiberG,
        e.sugarsG, e.addedSugarsG, e.starchG,
        e.satFatG, e.monoFatG, e.polyFatG, e.transFatG,
        e.cholesterolMg, e.omega3G, e.omega6G, e.omega9G,
        aminos?.hisMg, aminos?.ileMg, aminos?.leuMg, aminos?.lysMg, aminos?.metMg,
        aminos?.pheMg, aminos?.thrMg, aminos?.trpMg, aminos?.valMg, aminos?.cysMg, aminos?.tyrMg,
        ingredientsJson, aminoMatched, aminoTotal,
    )

    /**
     * Meal detail scaled by servings (same rules as [app.diurna.data.LibraryRepository] applyMeal).
     * Ingredient JSON and match counts are copied as-is; amino mg are scaled.
     */
    fun fromMeal(meal: MealEntity, servings: Double): Detail {
        fun o(x: Int?) = x?.let { (it * servings).roundToInt() }
        return Detail(
            o(meal.fiberG), o(meal.solubleFiberG), o(meal.insolubleFiberG),
            o(meal.sugarsG), o(meal.addedSugarsG), o(meal.starchG),
            o(meal.satFatG), o(meal.monoFatG), o(meal.polyFatG), o(meal.transFatG),
            o(meal.cholesterolMg), o(meal.omega3G), o(meal.omega6G), o(meal.omega9G),
            o(meal.hisMg), o(meal.ileMg), o(meal.leuMg), o(meal.lysMg), o(meal.metMg),
            o(meal.pheMg), o(meal.thrMg), o(meal.trpMg), o(meal.valMg), o(meal.cysMg), o(meal.tyrMg),
            meal.ingredientsJson, meal.aminoMatched, meal.aminoTotal,
        )
    }

    fun mealHasDetail(meal: MealEntity): Boolean = fromMeal(meal, 1.0).let { it.hasAnySubMacro || it.hasAnyAmino }

    /** Keep current when non-null; take incoming only for nulls. Then clamp to stored parents. */
    fun mergeNullsOnly(current: Detail, incoming: Detail, storedCarbs: Int?, storedFat: Int?): Detail {
        fun pick(cur: Int?, inc: Int?) = cur ?: inc
        val merged = Detail(
            pick(current.fiberG, incoming.fiberG),
            pick(current.solubleFiberG, incoming.solubleFiberG),
            pick(current.insolubleFiberG, incoming.insolubleFiberG),
            pick(current.sugarsG, incoming.sugarsG),
            pick(current.addedSugarsG, incoming.addedSugarsG),
            pick(current.starchG, incoming.starchG),
            pick(current.satFatG, incoming.satFatG),
            pick(current.monoFatG, incoming.monoFatG),
            pick(current.polyFatG, incoming.polyFatG),
            pick(current.transFatG, incoming.transFatG),
            pick(current.cholesterolMg, incoming.cholesterolMg),
            pick(current.omega3G, incoming.omega3G),
            pick(current.omega6G, incoming.omega6G),
            pick(current.omega9G, incoming.omega9G),
            pick(current.hisMg, incoming.hisMg),
            pick(current.ileMg, incoming.ileMg),
            pick(current.leuMg, incoming.leuMg),
            pick(current.lysMg, incoming.lysMg),
            pick(current.metMg, incoming.metMg),
            pick(current.pheMg, incoming.pheMg),
            pick(current.thrMg, incoming.thrMg),
            pick(current.trpMg, incoming.trpMg),
            pick(current.valMg, incoming.valMg),
            pick(current.cysMg, incoming.cysMg),
            pick(current.tyrMg, incoming.tyrMg),
            current.ingredientsJson ?: incoming.ingredientsJson,
            pick(current.aminoMatched, incoming.aminoMatched),
            pick(current.aminoTotal, incoming.aminoTotal),
        )
        return clampToParents(merged, storedCarbs, storedFat)
    }

    /**
     * Clamp carb/fat subtypes to the STORED parent macros so a divergent re-estimate cannot
     * produce fiber > carbs etc. Existing non-null values are left as-is by [mergeNullsOnly]
     * before this runs; we still clamp the whole detail so newly filled values stay consistent.
     */
    fun clampToParents(d: Detail, carbs: Int?, fat: Int?): Detail {
        fun c(v: Int?) = if (carbs == null || v == null) v else v.coerceAtMost(carbs).coerceAtLeast(0)
        fun f(v: Int?) = if (fat == null || v == null) v else v.coerceAtMost(fat).coerceAtLeast(0)
        return d.copy(
            fiberG = c(d.fiberG),
            solubleFiberG = c(d.solubleFiberG),
            insolubleFiberG = c(d.insolubleFiberG),
            sugarsG = c(d.sugarsG),
            addedSugarsG = c(d.addedSugarsG),
            starchG = c(d.starchG),
            satFatG = f(d.satFatG),
            monoFatG = f(d.monoFatG),
            polyFatG = f(d.polyFatG),
            transFatG = f(d.transFatG),
            omega3G = f(d.omega3G),
            omega6G = f(d.omega6G),
            omega9G = f(d.omega9G),
            // cholesterol is mg, not clamped to fat grams
        )
    }

    /** True when an AI entry still has nulls we can fill (sub-macros always; aminos when key set). */
    fun needsAiFill(entry: FoodEntryEntity, wantAminos: Boolean): Boolean {
        if (entry.source != FoodEntryEntity.SOURCE_AI) return false
        if (entry.estimatedAt == null) return false
        val d = fromEntry(entry)
        if (missingSubMacro(d)) return true
        if (wantAminos && missingAmino(d)) return true
        return false
    }

    fun needsMealAttempt(entry: FoodEntryEntity): Boolean {
        if (entry.source != FoodEntryEntity.SOURCE_MEAL) return false
        if (entry.estimatedAt == null) return false
        val d = fromEntry(entry)
        return missingSubMacro(d) || missingAmino(d)
    }

    private fun missingSubMacro(d: Detail): Boolean =
        d.fiberG == null || d.sugarsG == null || d.starchG == null ||
            d.satFatG == null || d.monoFatG == null || d.polyFatG == null ||
            d.transFatG == null || d.cholesterolMg == null ||
            d.omega3G == null || d.omega6G == null || d.omega9G == null ||
            d.solubleFiberG == null || d.insolubleFiberG == null || d.addedSugarsG == null

    private fun missingAmino(d: Detail): Boolean =
        d.hisMg == null || d.ileMg == null || d.leuMg == null || d.lysMg == null ||
            d.metMg == null || d.pheMg == null || d.thrMg == null || d.trpMg == null ||
            d.valMg == null || d.cysMg == null || d.tyrMg == null ||
            d.ingredientsJson == null || d.aminoMatched == null || d.aminoTotal == null

    /** Macros snapshot used to prove day totals stay identical after a merge. */
    data class MacroSnapshot(val kcalLow: Int?, val kcalHigh: Int?, val protein: Int?, val carbs: Int?, val fat: Int?)

    fun macrosOf(e: FoodEntryEntity) = MacroSnapshot(e.kcalLow, e.kcalHigh, e.proteinG, e.carbsG, e.fatG)

    fun sumMacros(entries: List<FoodEntryEntity>): MacroSnapshot {
        fun sum(pick: (FoodEntryEntity) -> Int?): Int? {
            var any = false
            var s = 0
            for (e in entries) {
                val v = pick(e) ?: continue
                any = true
                s += v
            }
            return if (any) s else null
        }
        return MacroSnapshot(
            sum { it.kcalLow }, sum { it.kcalHigh }, sum { it.proteinG },
            sum { it.carbsG }, sum { it.fatG },
        )
    }
}
