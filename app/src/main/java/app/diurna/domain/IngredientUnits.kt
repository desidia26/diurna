package app.diurna.domain

/**
 * Amount units for recipe ingredients. Mass (`g`) needs no density. Volume units need a density
 * in g/ml; without one, conversion refuses rather than silently assuming water.
 *
 * US culinary volumes (NIST Handbook 44 / FDA labeling practice):
 * 1 cup = 240 ml, 1 tbsp = 15 ml, 1 tsp = 5 ml (rounded labeling values).
 *
 * Densities are approximate culinary values (g/ml ≈ g/cm³), drawn from USDA FoodData Central
 * Foundation/SR Legacy foods where a household measure exists, and from common culinary tables
 * (water 1.00; vegetable oil ~0.92; all-purpose flour ~0.53; granulated sugar ~0.85; honey ~1.42).
 * They are good enough for home recipes, not lab precision.
 */
enum class IngredientUnit(val key: String, val label: String) {
    G("g", "g"),
    ML("ml", "ml"),
    CUP("cup", "cup"),
    TBSP("tbsp", "tbsp"),
    TSP("tsp", "tsp"),
    EACH("each", "each");

    val isVolume: Boolean get() = this == ML || this == CUP || this == TBSP || this == TSP
    val needsDensity: Boolean get() = isVolume
    /** `each` needs an explicit gram weight; density does not apply. */
    val needsGramsPerEach: Boolean get() = this == EACH

    companion object {
        fun of(key: String?): IngredientUnit =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: G
    }
}

data class DensityOption(val id: String, val label: String, val gPerMl: Double)

object IngredientDensities {
    /** Cited culinary / USDA-adjacent densities (g per ml). */
    val OPTIONS: List<DensityOption> = listOf(
        DensityOption("water", "Water / broth (~1.00)", 1.00),
        DensityOption("milk", "Milk (~1.03)", 1.03),
        DensityOption("oil", "Vegetable oil (~0.92)", 0.92),
        DensityOption("butter", "Butter, melted (~0.91)", 0.91),
        DensityOption("honey", "Honey (~1.42)", 1.42),
        DensityOption("flour", "All-purpose flour (~0.53)", 0.53),
        DensityOption("sugar", "Granulated sugar (~0.85)", 0.85),
        DensityOption("oats", "Rolled oats (~0.36)", 0.36),
        DensityOption("rice_dry", "White rice, dry (~0.78)", 0.78),
        DensityOption("salt", "Table salt (~1.22)", 1.22),
    )

    fun byId(id: String?): DensityOption? = OPTIONS.firstOrNull { it.id == id }
}

object IngredientUnits {
    /** ml per US labeling cup / spoon (FDA 21 CFR 101.9 household measures). */
    const val ML_PER_CUP = 240.0
    const val ML_PER_TBSP = 15.0
    const val ML_PER_TSP = 5.0

    /**
     * Convert [amount] in [unit] to grams.
     * - [densityGPerMl] required for volume units; null → null (refused).
     * - [gramsPerEach] required for `each`; null → null.
     */
    fun toGrams(
        amount: Double,
        unit: IngredientUnit,
        densityGPerMl: Double? = null,
        gramsPerEach: Double? = null,
    ): Double? {
        if (amount < 0 || !amount.isFinite()) return null
        return when (unit) {
            IngredientUnit.G -> amount
            IngredientUnit.ML -> densityGPerMl?.takeIf { it > 0 }?.let { amount * it }
            IngredientUnit.CUP -> densityGPerMl?.takeIf { it > 0 }?.let { amount * ML_PER_CUP * it }
            IngredientUnit.TBSP -> densityGPerMl?.takeIf { it > 0 }?.let { amount * ML_PER_TBSP * it }
            IngredientUnit.TSP -> densityGPerMl?.takeIf { it > 0 }?.let { amount * ML_PER_TSP * it }
            IngredientUnit.EACH -> gramsPerEach?.takeIf { it > 0 }?.let { amount * it }
        }
    }
}
