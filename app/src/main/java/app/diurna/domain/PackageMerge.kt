package app.diurna.domain

import kotlin.math.abs
import kotlin.math.max

/**
 * Merges what one package photo and the product databases say about a food into per-100 g values
 * with provenance. Keys are the per-100 g nutrient keys shared by the OFF snapshot, USDA branded
 * records and the package reader (kcal, protein_g, …, sodium_mg, vitamin_d_ug).
 *
 * Rules: each field independently takes the highest-precedence source that has it
 * (label > USDA branded > Open Food Facts); a missing value stays missing, never 0; lower sources
 * that disagree are kept on the field for the confirm screen, never averaged in.
 */
object PackageMerge {
    /** In precedence order. [MANUAL] is a value typed on the confirm screen; it's never a merge input. */
    enum class Source(val wire: String) {
        MANUAL("manual"), LABEL("label"), FDC_BRANDED("fdc_branded"), OFF("off");

        companion object {
            fun ofWire(w: String?): Source? = entries.firstOrNull { it.wire == w }
        }
    }

    enum class Flag {
        /** Label numbers are per serving but no source knows the serving's grams. */
        LABEL_NEEDS_SERVING_WEIGHT,
        /** Serving grams came from a database, not the photo. */
        SERVING_WEIGHT_FROM_DATABASE,
        /** Calories are far from 4·protein + 4·carbs + 9·fat. */
        KCAL_MACROS_MISMATCH,
    }

    /** One source's numbers: [perServing] (label) or [per100g] (databases). */
    data class Input(
        val source: Source,
        val per100g: Map<String, Double> = emptyMap(),
        val perServing: Map<String, Double> = emptyMap(),
        val servingGrams: Double? = null,
        val servingText: String? = null,
        val ingredients: String? = null,
    )

    data class Other(val source: Source, val value: Double)
    data class Fact(val value: Double, val source: Source, val disagreements: List<Other> = emptyList())

    data class Merged(
        val per100g: Map<String, Fact>,
        val servingGrams: Fact?,
        val servingText: String?,
        val ingredients: Pair<String, Source>?,
        val flags: Set<Flag>,
    ) {
        val hasNutrition: Boolean get() = per100g.containsKey("kcal")
        val hasDisagreements: Boolean
            get() = per100g.values.any { it.disagreements.isNotEmpty() } || servingGrams?.disagreements?.isNotEmpty() == true
    }

    fun merge(inputs: List<Input>): Merged {
        val ordered = inputs.sortedBy { it.source.ordinal }
        val flags = mutableSetOf<Flag>()

        val gramsBySource = ordered.mapNotNull { i -> i.servingGrams?.takeIf { it.isFinite() && it > 0 && it <= 2_000 }?.let { i.source to it } }
        val servingGrams = gramsBySource.firstOrNull()?.let { (src, g) ->
            Fact(g, src, gramsBySource.drop(1).filter { (_, o) -> abs(o - g) > 1.0 && abs(o - g) > 0.05 * g }.map { Other(it.first, it.second) })
        }

        val per100BySource = ordered.map { i ->
            val own = i.servingGrams?.takeIf { it.isFinite() && it > 0 && it <= 2_000 }
            val fromServing = if (i.perServing.isEmpty()) emptyMap() else {
                val grams = own ?: servingGrams?.value
                if (grams == null) {
                    flags += Flag.LABEL_NEEDS_SERVING_WEIGHT
                    emptyMap()
                } else {
                    if (own == null) flags += Flag.SERVING_WEIGHT_FROM_DATABASE
                    i.perServing.mapValues { (_, v) -> v * 100.0 / grams }
                }
            }
            i.source to (i.per100g + fromServing).filter { (k, v) -> plausible(k, v) }
        }

        val keys = per100BySource.flatMap { it.second.keys }.toSortedSet()
        val per100g = keys.associateWith { key ->
            val have = per100BySource.mapNotNull { (src, m) -> m[key]?.let { src to it } }
            val (src, v) = have.first()
            Fact(v, src, have.drop(1).filter { (_, o) -> disagree(key, v, o) }.map { Other(it.first, it.second) })
        }

        val kcal = per100g["kcal"]?.value
        val p = per100g["protein_g"]?.value
        val c = per100g["carbs_g"]?.value
        val f = per100g["fat_g"]?.value
        if (kcal != null && p != null && c != null && f != null) {
            val est = 4 * p + 4 * c + 9 * f
            if (abs(kcal - est) > max(0.2 * max(kcal, est), 20.0)) flags += Flag.KCAL_MACROS_MISMATCH
        }

        val ingredients = ordered.firstNotNullOfOrNull { i -> i.ingredients?.trim()?.takeIf { it.isNotEmpty() }?.let { it to i.source } }
        val servingText = ordered.firstNotNullOfOrNull { it.servingText?.trim()?.takeIf { t -> t.isNotEmpty() } }
        return Merged(per100g, servingGrams, servingText, ingredients, flags)
    }

    /** Amounts for [grams] eaten; empty for a non-positive or non-finite amount. */
    fun forGrams(m: Merged, grams: Double): Map<String, Double> {
        if (!grams.isFinite() || grams <= 0) return emptyMap()
        return m.per100g.mapValues { (_, f) -> f.value * grams / 100.0 }
    }

    /** Amounts for [servings]; null when the serving weight is unknown. */
    fun forServings(m: Merged, servings: Double): Map<String, Double>? {
        val g = m.servingGrams?.value ?: return null
        return forGrams(m, g * servings)
    }

    /** Within 10% (and a small absolute slack for rounding on the label) counts as agreeing. */
    private fun disagree(key: String, a: Double, b: Double): Boolean {
        val slack = when {
            key == "kcal" -> 10.0
            key.endsWith("_g") -> 1.0
            key.endsWith("_mg") -> 5.0
            else -> 1.0
        }
        val d = abs(a - b)
        return d > slack && d > 0.10 * max(abs(a), abs(b))
    }

    /** Per-100 g bounds; beyond them a value is a misread or vandalism, so it's dropped. */
    private fun plausible(key: String, v: Double): Boolean {
        if (!v.isFinite() || v < 0) return false
        val max = when {
            key == "kcal" -> 950.0
            key == "sodium_mg" -> 40_000.0
            key.endsWith("_mg") -> 10_000.0
            key.endsWith("_ug") -> 1_000.0
            key.endsWith("_g") -> 100.0
            else -> return false
        }
        return v <= max
    }
}
