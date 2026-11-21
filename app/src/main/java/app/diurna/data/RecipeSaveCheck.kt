package app.diurna.data

import app.diurna.domain.IngredientDensity
import app.diurna.domain.IngredientUnit
import app.diurna.domain.IngredientUnits

/**
 * What stops a recipe from saving, said per ingredient so the editor can show it where the person
 * looks instead of leaving Save to fail quietly. The rule itself is the repository's: a volume or
 * `each` line needs its weight in grams, because recipe totals are per gram and water is never assumed.
 */
object RecipeSaveCheck {
    /** [token] is a fixed debug-log reason. */
    enum class Need(val token: String) {
        DENSITY("needs_density"),
        GRAMS_PER_EACH("needs_grams_each"),
    }

    data class Blocker(val index: Int, val name: String, val need: Need)

    /** Fixed debug-log reason for a Save stopped by [blockers]. */
    const val REASON_NEEDS_WEIGHT = "needs_weight"

    fun needsMass(line: RecipeIngredientEntity): Boolean = need(line) != null

    fun need(line: RecipeIngredientEntity): Need? {
        if (line.grams != null) return null
        return when (IngredientUnit.of(line.unit)) {
            IngredientUnit.EACH -> Need.GRAMS_PER_EACH
            IngredientUnit.ML, IngredientUnit.CUP, IngredientUnit.TBSP, IngredientUnit.TSP -> Need.DENSITY
            IngredientUnit.G -> null
        }
    }

    fun blockers(lines: List<RecipeIngredientEntity>): List<Blocker> =
        lines.mapIndexedNotNull { i, l -> need(l)?.let { Blocker(i, l.name.ifBlank { "(unnamed)" }, it) } }

    /** One plain sentence naming each blocked ingredient, or null when Save can go ahead. */
    fun message(blockers: List<Blocker>): String? {
        if (blockers.isEmpty()) return null
        val names = blockers.joinToString(", ") { "\u201c${it.name}\u201d" }
        val what = if (blockers.size == 1) "needs its weight" else "need their weights"
        return "Can't save yet: $names $what in grams. Tap Weight on the line to enter it, or Remove it."
    }

    /**
     * [line] with its total weight typed by the person. Volume lines keep their unit and get a
     * matching g/ml, `each` lines a grams-per-each, so later amount edits still convert.
     */
    fun withWeight(line: RecipeIngredientEntity, grams: Double): RecipeIngredientEntity {
        require(grams > 0 && grams.isFinite()) { "grams must be positive" }
        val unit = IngredientUnit.of(line.unit)
        val manual = IngredientDensity.Source.MANUAL
        return when {
            unit.needsDensity -> {
                val ml = IngredientUnits.toGrams(line.amount, unit, 1.0, null)
                line.copy(grams = grams, gPerMl = ml?.takeIf { it > 0 }?.let { grams / it }, densityId = manual)
            }
            unit.needsGramsPerEach ->
                line.copy(grams = grams, gramsPerEach = if (line.amount > 0) grams / line.amount else null, densityId = manual)
            else -> line.copy(grams = grams, densityId = manual)
        }
    }
}
