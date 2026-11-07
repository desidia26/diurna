package app.diurna.ai

import app.diurna.data.fdc.FdcNutritionProfile
import app.diurna.domain.RecipeMath
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * AI per-100g nutrition for a single recipe ingredient when USDA FDC has no usable match
 * (or no USDA key). Macros are required; detail fields are optional.
 *
 * Zero macros are valid (water, salt). Returning nulls / omitting macros is a parse miss.
 */
object IngredientNutritionPrompt {
    const val SYSTEM = """You estimate nutrition per 100 grams for a single culinary ingredient.

Return JSON only:
- kcal_per_100: kilocalories per 100 g (integer 0–900). Water/salt ≈ 0; flour ≈ 360; oil ≈ 880.
- protein_g_per_100: grams protein per 100 g (0–100).
- carbs_g_per_100: grams carbohydrate per 100 g (0–100).
- fat_g_per_100: grams fat per 100 g (0–100).
- fiber_g_per_100: grams fiber per 100 g, or null when unknown.
- sugars_g_per_100: grams total sugars per 100 g, or null when unknown.
- basis: short reason under 80 characters (e.g. "USDA AP flour ~364 kcal/100g").
- note: empty string, or a brief caveat under 60 characters.

Rules:
- Values are for 100 g of the edible ingredient as typically used in recipes.
- Clean names mentally: ignore asterisks, parenthetical asides, "to taste".
- Water, broth, and similar have ~0 kcal and 0 macros — return zeros, not nulls.
- Salt and baking powder are ~0 kcal / macros.
- Soy milk is not dairy milk; use soy beverage figures (~30–50 kcal, some protein).
- If the ingredient is unknown, still return best-effort numbers with note "uncertain".
- Do not include density / g_per_ml. Nutrition only.

Examples:
"all-purpose flour" → {"kcal_per_100":364,"protein_g_per_100":10,"carbs_g_per_100":76,"fat_g_per_100":1,"fiber_g_per_100":3,"sugars_g_per_100":null,"basis":"AP flour ~364 kcal/100g","note":""}
"water" → {"kcal_per_100":0,"protein_g_per_100":0,"carbs_g_per_100":0,"fat_g_per_100":0,"fiber_g_per_100":0,"sugars_g_per_100":0,"basis":"water","note":""}
"soy milk" → {"kcal_per_100":43,"protein_g_per_100":3,"carbs_g_per_100":5,"fat_g_per_100":2,"fiber_g_per_100":1,"sugars_g_per_100":4,"basis":"unsweetened soy beverage","note":""}
"""

    private val NUM = mapOf("type" to listOf("number", "null"))
    private val INT_OR_NULL = mapOf("type" to listOf("integer", "null"))
    private val STR = mapOf("type" to "string")

    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "kcal_per_100" to INT_OR_NULL,
            "protein_g_per_100" to INT_OR_NULL,
            "carbs_g_per_100" to INT_OR_NULL,
            "fat_g_per_100" to INT_OR_NULL,
            "fiber_g_per_100" to INT_OR_NULL,
            "sugars_g_per_100" to INT_OR_NULL,
            "basis" to STR,
            "note" to STR,
        ),
        "required" to listOf(
            "kcal_per_100", "protein_g_per_100", "carbs_g_per_100", "fat_g_per_100",
            "fiber_g_per_100", "sugars_g_per_100", "basis", "note",
        ),
        "additionalProperties" to false,
    )

    fun userText(name: String): String =
        "Ingredient: ${name.trim().take(120)}\nbasis: per 100 grams"

    private val mapper = ObjectMapper()

    /**
     * Parse model JSON into a [FdcNutritionProfile]-shaped result (fdcId=0, source later).
     * Structurally valid JSON with all four macros present (including zeros) succeeds.
     * Missing macro fields → [EstimateFailed] (blocking parse failure for evals).
     */
    fun parse(json: String): FdcNutritionProfile {
        val node: JsonNode = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject }
            ?: throw EstimateFailed("Couldn't read nutrition estimate")
        fun reqInt(name: String): Int {
            val n = node.get(name) ?: throw EstimateFailed("Missing $name")
            if (n.isNull) throw EstimateFailed("Missing $name")
            if (!n.isNumber) throw EstimateFailed("Bad $name")
            val v = n.asInt()
            if (v < 0) throw EstimateFailed("Negative $name")
            return v
        }
        fun optInt(name: String): Int? {
            val n = node.get(name) ?: return null
            if (n.isNull) return null
            if (!n.isNumber) return null
            return n.asInt().coerceAtLeast(0)
        }
        fun text(name: String): String =
            node.get(name)?.takeIf { it.isTextual }?.asText().orEmpty().trim()

        val kcal = reqInt("kcal_per_100").coerceIn(0, 900)
        val protein = reqInt("protein_g_per_100").coerceIn(0, 100)
        val carbs = reqInt("carbs_g_per_100").coerceIn(0, 100)
        val fat = reqInt("fat_g_per_100").coerceIn(0, 100)
        val fiber = optInt("fiber_g_per_100")?.coerceIn(0, 100)
        val sugars = optInt("sugars_g_per_100")?.coerceIn(0, 100)
        val basis = text("basis").take(80)
        val note = text("note").take(60)
        val desc = basis.ifBlank { note }.ifBlank { "AI estimate" }
        return FdcNutritionProfile(
            fdcId = 0,
            description = desc,
            per100 = RecipeMath.IngredientNutrition(
                proteinG = protein,
                carbsG = carbs,
                fatG = fat,
                fiberG = fiber,
                sugarsG = sugars,
            ),
            kcalPer100 = kcal,
        )
    }
}
