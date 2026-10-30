package app.diurna.ai

import app.diurna.domain.FoodIngredient
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Context for guessing barcode-product ingredients when Open Food Facts lacks usable
 * percentage data. Label macros are hints only — the model must not revise them.
 */
data class BarcodeIngredientsContext(
    val productName: String? = null,
    val brand: String? = null,
    val servingGrams: Double,
    val servingSize: String? = null,
    val ingredientsText: String? = null,
    val kcal: Int? = null,
    val proteinG: Int? = null,
    val carbsG: Int? = null,
    val fatG: Int? = null,
)

/**
 * Dedicated prompt for barcode amino fallback: returns only an ingredient list
 * ({name, grams}) for USDA FDC lookup. Never invents amino milligrams or changes macros.
 */
object BarcodeIngredientsPrompt {
    val SYSTEM: String = """
You list the edible ingredients of one packaged food product so the app can look up amino acids in USDA FoodData Central.

Rules:
- Return only ingredients: an array of {name, grams} for one serving.
- name: generic, FDC-friendly wording (e.g. "wheat flour", "soybean oil", "chicken breast, cooked"). Prefer Foundation/SR Legacy style; avoid brand names and marketing fluff.
- grams: estimated edible weight of that ingredient in this serving. Prefer summing near the serving grams when known.
- Use the ingredients text (ordered by weight when present) as the primary guide. Split compound phrases into separate ingredients when that helps FDC matching.
- Label calories and macros are already recorded — treat them as hints for relative amounts only. Do NOT invent or revise kcal, protein, carbs, or fat.
- Do NOT invent amino-acid milligrams.
- Omit water when it is only a processing medium with no meaningful weight; keep major ingredients including oils and sugars.
- If the product is not food or you cannot name any ingredients, return an empty ingredients array.
""".trimIndent()

    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "ingredients" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string"),
                        "grams" to mapOf("type" to "number"),
                    ),
                    "required" to listOf("name", "grams"),
                    "additionalProperties" to false,
                ),
            ),
        ),
        "required" to listOf("ingredients"),
        "additionalProperties" to false,
    )

    fun userMessage(ctx: BarcodeIngredientsContext): String {
        val lines = ArrayList<String>()
        ctx.productName?.takeIf { it.isNotBlank() }?.let { lines += "Product: $it" }
        ctx.brand?.takeIf { it.isNotBlank() }?.let { lines += "Brand: $it" }
        if (ctx.servingGrams > 0 && ctx.servingGrams.isFinite()) {
            lines += "Serving weight: ${trimNum(ctx.servingGrams)} g"
        }
        ctx.servingSize?.takeIf { it.isNotBlank() }?.let { lines += "Serving size label: $it" }
        val macros = listOfNotNull(
            ctx.kcal?.let { "kcal=$it" },
            ctx.proteinG?.let { "protein_g=$it" },
            ctx.carbsG?.let { "carbs_g=$it" },
            ctx.fatG?.let { "fat_g=$it" },
        )
        if (macros.isNotEmpty()) lines += "Label macros (do not change): ${macros.joinToString(", ")}"
        val text = ctx.ingredientsText?.trim().orEmpty()
        lines += if (text.isNotEmpty()) "Ingredients text: $text" else "Ingredients text: (none provided)"
        lines += "List ingredients with grams for this one serving."
        return lines.joinToString("\n")
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else ((v * 10).toInt() / 10.0).toString()

    private val mapper = ObjectMapper()

    fun parse(json: String): List<FoodIngredient> {
        val node = runCatching { mapper.readTree(json) }.getOrNull()
            ?: throw EstimateFailed("Couldn't read barcode ingredients")
        return EstimatePrompt.parseIngredients(node.get("ingredients"))
            ?: emptyList()
    }
}
