package app.diurna.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Nutrition guess for one free-text food entry. Calories are a range; macros are point estimates in grams. */
data class FoodEstimate(
    val kcalLow: Int,
    val kcalHigh: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    /** What the estimate assumed, e.g. "1 cup rice, no guac". */
    val note: String,
    /** Which model made it, when the estimator knows better than its own [FoodEstimator.model]. */
    val model: String? = null,
)

/** Behind an interface so tests use a fake and never call the real API. */
interface FoodEstimator {
    /** Recorded on each entry, so a better model can re-estimate history later. */
    val model: String

    /** Shown on every waiting entry when the last allowed attempt still can't get through. */
    val gaveUpMessage: String get() = EstimateErrors.GAVE_UP

    suspend fun estimate(text: String): FoodEstimate
}

/** Retrying won't help: bad key, refused request, unparseable reply. Shown on the entry. */
class EstimateFailed(message: String) : Exception(message)

/** Missing key: entries wait until one is added in Setup. */
class NoApiKey(message: String = "Add an Anthropic API key in Setup to estimate food") : Exception(message)

object EstimatePrompt {
    const val SYSTEM = """You estimate nutrition for one food log entry, written casually by one person about what they ate.

- Assume typical US portions and preparation unless the entry says otherwise. Restaurant and chain items: use their usual serving.
- Calories: give a realistic low and high for this entry; widen the range when the portion or recipe is genuinely uncertain.
- Protein, carbs and fat: one best estimate each, in grams, for the middle of the calorie range.
- note: the assumptions that matter most for the numbers (portion sizes, cooking fat, sides), under 80 characters. The person corrects the estimate by editing their entry, so name what they would need to change.
- If the entry isn't food or drink, return zeros and note "not food"."""

    /** JSON schema for structured output: guarantees the reply parses into [FoodEstimate]. */
    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "kcal_low" to mapOf("type" to "integer"),
            "kcal_high" to mapOf("type" to "integer"),
            "protein_g" to mapOf("type" to "integer"),
            "carbs_g" to mapOf("type" to "integer"),
            "fat_g" to mapOf("type" to "integer"),
            "note" to mapOf("type" to "string"),
        ),
        "required" to listOf("kcal_low", "kcal_high", "protein_g", "carbs_g", "fat_g", "note"),
        "additionalProperties" to false,
    )

    private val mapper = ObjectMapper()

    /** Parses the structured reply; guards ordering and negatives in case the model slips. */
    fun parse(json: String): FoodEstimate {
        val node: JsonNode = runCatching { mapper.readTree(json) }.getOrNull()
            ?: throw EstimateFailed("Couldn't read the estimate")
        fun int(name: String): Int = node.get(name)?.takeIf { it.canConvertToInt() }?.intValue()?.coerceAtLeast(0)
            ?: throw EstimateFailed("Estimate was missing $name")
        val a = int("kcal_low")
        val b = int("kcal_high")
        return FoodEstimate(
            kcalLow = minOf(a, b), kcalHigh = maxOf(a, b),
            proteinG = int("protein_g"), carbsG = int("carbs_g"), fatG = int("fat_g"),
            note = node.get("note")?.asText().orEmpty().trim(),
        )
    }
}
