package app.diurna.domain

import kotlin.math.roundToInt

/**
 * Sums recipe ingredient snapshots into per-serving meal totals.
 * Required P/C/F treat missing as 0; optional fields stay null when every ingredient left them unknown.
 * Amino coverage reuses matched/total: an ingredient "matches" when it has any amino value.
 */
object RecipeMath {
    data class IngredientNutrition(
        val proteinG: Int = 0,
        val carbsG: Int = 0,
        val fatG: Int = 0,
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
        val aminos: AminoAcids = AminoAcids(),
    )

    data class Totals(
        val kcal: Int,
        val proteinG: Int,
        val carbsG: Int,
        val fatG: Int,
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
        val aminos: AminoAcids = AminoAcids(),
        val aminoMatched: Int? = null,
        val aminoTotal: Int? = null,
    ) {
        val aminoPartial: Boolean
            get() = aminoMatched != null && aminoTotal != null &&
                aminoMatched > 0 && aminoMatched < aminoTotal
    }

    /** Divide batch totals by [servingsYield] (≥ 0.01) into one serving. */
    fun perServing(batch: List<IngredientNutrition>, servingsYield: Double): Totals {
        val yield = servingsYield.coerceAtLeast(0.01)
        val protein = batch.sumOf { it.proteinG }
        val carbs = batch.sumOf { it.carbsG }
        val fat = batch.sumOf { it.fatG }
        fun opt(pick: (IngredientNutrition) -> Int?): Int? {
            var any = false
            var s = 0
            for (i in batch) {
                val v = pick(i) ?: continue
                any = true
                s += v
            }
            return if (any) s else null
        }
        val fiber = opt { it.fiberG }
        val sol = opt { it.solubleFiberG }
        val insol = opt { it.insolubleFiberG }
        val sugars = opt { it.sugarsG }
        val added = opt { it.addedSugarsG }
        val starch = opt { it.starchG }
        val sat = opt { it.satFatG }
        val mono = opt { it.monoFatG }
        val poly = opt { it.polyFatG }
        val trans = opt { it.transFatG }
        val chol = opt { it.cholesterolMg }
        val o3 = opt { it.omega3G }
        val o6 = opt { it.omega6G }
        val o9 = opt { it.omega9G }
        val aminos = AminoAcids.sum(batch.map { it.aminos })
        val matched = batch.count { it.aminos.hasAny }
        val total = batch.size
        val factor = 1.0 / yield
        fun s(v: Int) = (v * factor).roundToInt().coerceAtLeast(0)
        fun o(v: Int?) = v?.let { (it * factor).roundToInt().coerceAtLeast(0) }
        val p = s(protein)
        val c = s(carbs)
        val f = s(fat)
        val fi = o(fiber)
        val so = o(sol)
        val inn = o(insol)
        val kcal = MacroEnergy.kcal(p, c, f, fi, so, inn).total.roundToInt().coerceAtLeast(0)
        return Totals(
            kcal = kcal,
            proteinG = p,
            carbsG = c,
            fatG = f,
            fiberG = fi,
            solubleFiberG = so,
            insolubleFiberG = inn,
            sugarsG = o(sugars),
            addedSugarsG = o(added),
            starchG = o(starch),
            satFatG = o(sat),
            monoFatG = o(mono),
            polyFatG = o(poly),
            transFatG = o(trans),
            cholesterolMg = o(chol),
            omega3G = o(o3),
            omega6G = o(o6),
            omega9G = o(o9),
            aminos = aminos.scale(factor),
            aminoMatched = if (total == 0) null else matched,
            aminoTotal = if (total == 0) null else total,
        )
    }

    /** Scale a per-100g nutrition profile by [grams]. */
    fun scalePer100(per100: IngredientNutrition, grams: Double): IngredientNutrition {
        val k = (grams / 100.0).coerceAtLeast(0.0)
        fun s(v: Int) = (v * k).roundToInt().coerceAtLeast(0)
        fun o(v: Int?) = v?.let { (it * k).roundToInt().coerceAtLeast(0) }
        return IngredientNutrition(
            proteinG = s(per100.proteinG),
            carbsG = s(per100.carbsG),
            fatG = s(per100.fatG),
            fiberG = o(per100.fiberG),
            solubleFiberG = o(per100.solubleFiberG),
            insolubleFiberG = o(per100.insolubleFiberG),
            sugarsG = o(per100.sugarsG),
            addedSugarsG = o(per100.addedSugarsG),
            starchG = o(per100.starchG),
            satFatG = o(per100.satFatG),
            monoFatG = o(per100.monoFatG),
            polyFatG = o(per100.polyFatG),
            transFatG = o(per100.transFatG),
            cholesterolMg = o(per100.cholesterolMg),
            omega3G = o(per100.omega3G),
            omega6G = o(per100.omega6G),
            omega9G = o(per100.omega9G),
            aminos = per100.aminos.scale(k),
        )
    }
}
