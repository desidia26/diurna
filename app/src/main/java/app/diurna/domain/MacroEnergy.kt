package app.diurna.domain

/**
 * Calories from macros with a fiber adjustment. Stored entry kcal ranges are left alone; this is for
 * Metrics stacks and any other place that derives kcal from grams.
 *
 * Formula: P×4 + F×9 + (carbs − fiber)×4 + fiber energy, with carbs − fiber clamped at 0.
 * Fiber energy: soluble×2 + insoluble×0 when both splits are known; else total fiber×2; else 0
 * (plain carbs×4). Carbs are total carbs including fiber (US label style).
 */
object MacroEnergy {
    data class Parts(
        val proteinKcal: Double,
        /** Available-carb kcal after subtracting fiber grams. */
        val carbsKcal: Double,
        val fiberKcal: Double,
        val fatKcal: Double,
    ) {
        val total: Double get() = proteinKcal + carbsKcal + fiberKcal + fatKcal
    }

    /**
     * [carbsG] is total carbs including fiber. Missing gram counts count as 0 for P/C/F; fiber
     * unknowns mean "no fiber adjustment" rather than zero fiber.
     */
    fun kcal(
        proteinG: Int?,
        carbsG: Int?,
        fatG: Int?,
        fiberG: Int? = null,
        solubleFiberG: Int? = null,
        insolubleFiberG: Int? = null,
    ): Parts {
        val protein = (proteinG ?: 0).coerceAtLeast(0)
        val carbs = (carbsG ?: 0).coerceAtLeast(0)
        val fat = (fatG ?: 0).coerceAtLeast(0)
        val fiberGrams: Int
        val fiberKcal: Double
        when {
            solubleFiberG != null && insolubleFiberG != null -> {
                val sol = solubleFiberG.coerceAtLeast(0)
                val insol = insolubleFiberG.coerceAtLeast(0)
                fiberGrams = sol + insol
                fiberKcal = sol * 2.0 + insol * 0.0
            }
            fiberG != null -> {
                fiberGrams = fiberG.coerceAtLeast(0)
                fiberKcal = fiberGrams * 2.0
            }
            else -> {
                fiberGrams = 0
                fiberKcal = 0.0
            }
        }
        val available = (carbs - fiberGrams).coerceAtLeast(0)
        return Parts(
            proteinKcal = protein * 4.0,
            carbsKcal = available * 4.0,
            fiberKcal = fiberKcal,
            fatKcal = fat * 9.0,
        )
    }
}
