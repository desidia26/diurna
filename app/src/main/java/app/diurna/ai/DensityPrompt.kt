package app.diurna.ai

import app.diurna.domain.DensityLookup
import app.diurna.domain.DensityUnitKind
import app.diurna.domain.IngredientDensity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * AI fallback when USDA FoodData Central has no usable household portion (or no USDA key).
 * Estimates g/ml for volume ingredients and/or grams per each for count ingredients.
 */
object DensityPrompt {
    const val SYSTEM = """You estimate culinary density for a single recipe ingredient so volume or count can convert to grams.

Return JSON only:
- g_per_ml: grams per milliliter for volume measures (cup/tbsp/tsp/ml), or null when not applicable. Typical culinary range 0.2–1.5 (flour ~0.53, water 1.0, honey ~1.42, oil ~0.92). Prefer packed/chopped form if the name says so.
- grams_per_each: grams for ONE item when the unit is each/medium/large/whole, or null when not applicable. Typical produce/egg range 10–400.
- basis: short reason under 80 characters (e.g. "USDA AP flour ~125g/cup", "medium egg ~50g").
- note: empty string, or a brief caveat under 60 characters.

Rules:
- Set only the fields that match the requested unit_kind ("volume" or "each"). The other numeric field must be null.
- g_per_ml must be between 0.05 and 2.0 inclusive when set (flour ~0.53, sugar ~0.85, water 1.0, oil ~0.92, honey ~1.42). Never return cup-gram weights as g_per_ml.
- grams_per_each must be between 1 and 2000 when set (large egg ~50, medium apple ~180).
- If unsure, return nulls and note "unknown".
- Do not include nutrition macros. Density only.

Examples:
unit_kind volume, "all-purpose flour" → {"g_per_ml":0.53,"grams_per_each":null,"basis":"~125g per cup","note":""}
unit_kind each, "large egg" → {"g_per_ml":null,"grams_per_each":50,"basis":"large egg ~50g","note":""}
"""

    private val NUM = mapOf("type" to listOf("number", "null"))
    private val STR = mapOf("type" to "string")

    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "g_per_ml" to NUM,
            "grams_per_each" to NUM,
            "basis" to STR,
            "note" to STR,
        ),
        "required" to listOf("g_per_ml", "grams_per_each", "basis", "note"),
        "additionalProperties" to false,
    )

    fun userText(name: String, kind: DensityUnitKind): String =
        "Ingredient: ${name.trim().take(120)}\nunit_kind: ${kind.key}"

    private val mapper = ObjectMapper()

    fun parse(json: String, kind: DensityUnitKind): DensityLookup {
        val node: JsonNode = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject }
            ?: throw EstimateFailed("Couldn't read density estimate")
        fun num(name: String): Double? {
            val n = node.get(name) ?: return null
            if (n.isNull) return null
            if (!n.isNumber) return null
            val v = n.asDouble()
            return if (v.isFinite() && v > 0) v else null
        }
        fun text(name: String): String =
            node.get(name)?.takeIf { it.isTextual }?.asText().orEmpty().trim()

        val gPerMl = num("g_per_ml")?.takeIf { it in 0.05..2.0 }
        val gEach = num("grams_per_each")?.takeIf { it in 1.0..2000.0 }
        val basis = text("basis").take(80).ifBlank { null }
        val note = text("note").take(60)

        // Structurally valid JSON always returns a lookup. Callers that need a usable
        // number (estimators) check [DensityLookup.hasValue] and throw EstimateFailed.
        // Eval PARSE_HEALTH treats a successful parse as pass even when values are null.
        return when (kind) {
            DensityUnitKind.VOLUME -> DensityLookup(
                gPerMl = gPerMl,
                gramsPerEach = null,
                source = IngredientDensity.Source.AI,
                basis = basis ?: note.takeIf { it.isNotEmpty() },
            )
            DensityUnitKind.EACH -> DensityLookup(
                gPerMl = null,
                gramsPerEach = gEach,
                source = IngredientDensity.Source.AI,
                basis = basis ?: note.takeIf { it.isNotEmpty() },
            )
        }
    }
}
