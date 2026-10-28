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
    // Detailed nutrition (nullable = unknown). Only filled when the Detailed nutrition setting is on.
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
)

/** Behind an interface so tests use a fake and never call the real API. */
interface FoodEstimator {
    /** Recorded on each entry, so a better model can re-estimate history later. */
    val model: String

    /** Shown on every waiting entry when the last allowed attempt still can't get through. */
    val gaveUpMessage: String get() = EstimateErrors.GAVE_UP

    /**
     * [photo]: an optional JPEG of the food (already downscaled), sent along with the text.
     * [detailed]: when true, the prompt and schema also ask for fiber / sugars / lipid breakdown.
     */
    suspend fun estimate(text: String, photo: ByteArray? = null, detailed: Boolean = false): FoodEstimate
}

/** Retrying won't help: bad key, refused request, unparseable reply. Shown on the entry. */
class EstimateFailed(message: String) : Exception(message)

/** Missing key: entries wait until one is added in Setup. */
class NoApiKey(message: String = "Add an Anthropic API key in Setup to estimate food") : Exception(message)

object EstimatePrompt {
    private const val BASE = """You estimate nutrition for one food log entry, written casually by one person about what they ate.

- Assume typical US portions and preparation unless the entry says otherwise. Restaurant and chain items: use their usual serving.
- Calories: give a realistic low and high for this entry; widen the range when the portion or recipe is genuinely uncertain.
- Protein, carbs and fat: one best estimate each, in grams, for the middle of the calorie range. Carbs are TOTAL carbs including fiber (US nutrition-label style).
- note: the assumptions that matter most for the numbers (portion sizes, cooking fat, sides), under 80 characters. The person corrects the estimate by editing their entry, so name what they would need to change.
- A photo of the food may come with the entry. Use it to judge portion size, preparation and anything the text leaves out; where the text and the photo disagree, the text wins.
- If the entry isn't food or drink, return zeros and note "not food"."""

    private const val DETAIL = """

Detailed breakdown (optional integers; use null when you truly cannot estimate a value — never invent 0):
- fiber_g, and when known soluble_fiber_g / insoluble_fiber_g (their sum should match fiber_g).
- sugars_g, added_sugars_g, starch_g (each ≤ carbs; added ≤ sugars).
- sat_fat_g, mono_fat_g, poly_fat_g, trans_fat_g (each ≤ fat; sum ≈ fat when all known).
- cholesterol_mg; omega3_g, omega6_g, omega9_g (each ≤ fat)."""

    /** Basic system prompt (Detailed nutrition off). Kept as [SYSTEM] for existing call sites/tests. */
    val SYSTEM: String = BASE

    fun system(detailed: Boolean): String = if (detailed) BASE + DETAIL else BASE

    private val BASE_PROPS: Map<String, Any> = mapOf(
        "kcal_low" to mapOf("type" to "integer"),
        "kcal_high" to mapOf("type" to "integer"),
        "protein_g" to mapOf("type" to "integer"),
        "carbs_g" to mapOf("type" to "integer"),
        "fat_g" to mapOf("type" to "integer"),
        "note" to mapOf("type" to "string"),
    )

    private val DETAIL_PROPS: Map<String, Any> = mapOf(
        "fiber_g" to mapOf("type" to listOf("integer", "null")),
        "soluble_fiber_g" to mapOf("type" to listOf("integer", "null")),
        "insoluble_fiber_g" to mapOf("type" to listOf("integer", "null")),
        "sugars_g" to mapOf("type" to listOf("integer", "null")),
        "added_sugars_g" to mapOf("type" to listOf("integer", "null")),
        "starch_g" to mapOf("type" to listOf("integer", "null")),
        "sat_fat_g" to mapOf("type" to listOf("integer", "null")),
        "mono_fat_g" to mapOf("type" to listOf("integer", "null")),
        "poly_fat_g" to mapOf("type" to listOf("integer", "null")),
        "trans_fat_g" to mapOf("type" to listOf("integer", "null")),
        "cholesterol_mg" to mapOf("type" to listOf("integer", "null")),
        "omega3_g" to mapOf("type" to listOf("integer", "null")),
        "omega6_g" to mapOf("type" to listOf("integer", "null")),
        "omega9_g" to mapOf("type" to listOf("integer", "null")),
    )

    private val BASE_REQUIRED = listOf("kcal_low", "kcal_high", "protein_g", "carbs_g", "fat_g", "note")

    /** JSON schema for structured output: guarantees the reply parses into [FoodEstimate]. */
    val SCHEMA: Map<String, Any> = schema(detailed = false)

    fun schema(detailed: Boolean): Map<String, Any> {
        val props = if (detailed) BASE_PROPS + DETAIL_PROPS else BASE_PROPS
        // Strict schemas (xAI) require every property listed in required; nullables cover "unknown".
        val required = if (detailed) BASE_REQUIRED + DETAIL_PROPS.keys.toList() else BASE_REQUIRED
        return mapOf(
            "type" to "object",
            "properties" to props,
            "required" to required,
            "additionalProperties" to false,
        )
    }

    private val mapper = ObjectMapper()

    private const val MAX_G = 1_000
    private const val MAX_CHOL_MG = 5_000
    /** Fat-subtype sum may overshoot fat by this many grams before we scale them down. */
    private const val FAT_SUM_TOLERANCE = 2

    /** Parses the structured reply; guards ordering and negatives in case the model slips. */
    fun parse(json: String): FoodEstimate {
        val node: JsonNode = runCatching { mapper.readTree(json) }.getOrNull()
            ?: throw EstimateFailed("Couldn't read the estimate")
        fun int(name: String): Int = node.get(name)?.takeIf { it.canConvertToInt() }?.intValue()?.coerceAtLeast(0)
            ?: throw EstimateFailed("Estimate was missing $name")
        /** Missing or JSON null → unknown; present (including 0) → clamped. */
        fun optG(name: String): Int? {
            val n = node.get(name) ?: return null
            if (n.isNull) return null
            if (!n.canConvertToInt()) return null
            return n.intValue().coerceIn(0, MAX_G)
        }
        fun optChol(): Int? {
            val n = node.get("cholesterol_mg") ?: return null
            if (n.isNull) return null
            if (!n.canConvertToInt()) return null
            return n.intValue().coerceIn(0, MAX_CHOL_MG)
        }
        val a = int("kcal_low")
        val b = int("kcal_high")
        val protein = int("protein_g").coerceAtMost(MAX_G)
        val carbs = int("carbs_g").coerceAtMost(MAX_G)
        val fat = int("fat_g").coerceAtMost(MAX_G)
        val detail = clampDetail(
            carbsG = carbs,
            fatG = fat,
            fiberG = optG("fiber_g"),
            solubleFiberG = optG("soluble_fiber_g"),
            insolubleFiberG = optG("insoluble_fiber_g"),
            sugarsG = optG("sugars_g"),
            addedSugarsG = optG("added_sugars_g"),
            starchG = optG("starch_g"),
            satFatG = optG("sat_fat_g"),
            monoFatG = optG("mono_fat_g"),
            polyFatG = optG("poly_fat_g"),
            transFatG = optG("trans_fat_g"),
            cholesterolMg = optChol(),
            omega3G = optG("omega3_g"),
            omega6G = optG("omega6_g"),
            omega9G = optG("omega9_g"),
        )
        return FoodEstimate(
            kcalLow = minOf(a, b), kcalHigh = maxOf(a, b),
            proteinG = protein, carbsG = carbs, fatG = fat,
            note = sanitizeNote(node.get("note")?.asText().orEmpty()),
            fiberG = detail.fiberG,
            solubleFiberG = detail.solubleFiberG,
            insolubleFiberG = detail.insolubleFiberG,
            sugarsG = detail.sugarsG,
            addedSugarsG = detail.addedSugarsG,
            starchG = detail.starchG,
            satFatG = detail.satFatG,
            monoFatG = detail.monoFatG,
            polyFatG = detail.polyFatG,
            transFatG = detail.transFatG,
            cholesterolMg = detail.cholesterolMg,
            omega3G = detail.omega3G,
            omega6G = detail.omega6G,
            omega9G = detail.omega9G,
        )
    }

    /** Consistency clamps for optional sub-macros. Pure; unit-tested. */
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
    )

    fun clampDetail(
        carbsG: Int,
        fatG: Int,
        fiberG: Int? = null,
        solubleFiberG: Int? = null,
        insolubleFiberG: Int? = null,
        sugarsG: Int? = null,
        addedSugarsG: Int? = null,
        starchG: Int? = null,
        satFatG: Int? = null,
        monoFatG: Int? = null,
        polyFatG: Int? = null,
        transFatG: Int? = null,
        cholesterolMg: Int? = null,
        omega3G: Int? = null,
        omega6G: Int? = null,
        omega9G: Int? = null,
    ): Detail {
        val carbs = carbsG.coerceAtLeast(0)
        val fat = fatG.coerceAtLeast(0)
        var sol = solubleFiberG?.coerceIn(0, MAX_G)
        var insol = insolubleFiberG?.coerceIn(0, MAX_G)
        var fiber = fiberG?.coerceIn(0, MAX_G)
        if (fiber == null && sol != null && insol != null) fiber = sol + insol
        if (fiber != null) {
            fiber = fiber.coerceAtMost(carbs)
            // Splits shouldn't exceed total fiber.
            if (sol != null && insol != null) {
                val sum = sol + insol
                if (sum > fiber) {
                    // Prefer keeping the ratio; if fiber is 0, drop splits to 0.
                    if (fiber == 0 || sum == 0) {
                        sol = 0; insol = 0
                    } else {
                        sol = ((sol.toDouble() * fiber) / sum).toInt().coerceIn(0, fiber)
                        insol = (fiber - sol).coerceAtLeast(0)
                    }
                }
            } else {
                sol = sol?.coerceAtMost(fiber)
                insol = insol?.coerceAtMost(fiber)
            }
        }
        var sugars = sugarsG?.coerceIn(0, carbs)
        var added = addedSugarsG?.coerceIn(0, MAX_G)
        if (sugars != null && added != null) added = added.coerceAtMost(sugars)
        else if (sugars == null && added != null) added = added.coerceAtMost(carbs)
        val starch = starchG?.coerceIn(0, carbs)

        fun fatPart(v: Int?) = v?.coerceIn(0, fat)
        var sat = fatPart(satFatG)
        var mono = fatPart(monoFatG)
        var poly = fatPart(polyFatG)
        var trans = fatPart(transFatG)
        val parts = listOfNotNull(sat, mono, poly, trans)
        if (parts.size == 4) {
            val sum = parts.sum()
            if (sum > fat + FAT_SUM_TOLERANCE && sum > 0 && fat > 0) {
                sat = ((sat!! * fat) / sum)
                mono = ((mono!! * fat) / sum)
                poly = ((poly!! * fat) / sum)
                trans = (fat - sat - mono - poly).coerceAtLeast(0)
            } else if (sum > fat + FAT_SUM_TOLERANCE && fat == 0) {
                sat = 0; mono = 0; poly = 0; trans = 0
            }
        }
        return Detail(
            fiberG = fiber,
            solubleFiberG = sol,
            insolubleFiberG = insol,
            sugarsG = sugars,
            addedSugarsG = added,
            starchG = starch,
            satFatG = sat,
            monoFatG = mono,
            polyFatG = poly,
            transFatG = trans,
            cholesterolMg = cholesterolMg?.coerceIn(0, MAX_CHOL_MG),
            omega3G = fatPart(omega3G),
            omega6G = fatPart(omega6G),
            omega9G = fatPart(omega9G),
        )
    }
    /** Keeps the note short and free of control characters; the model is asked for under 80. */
    fun sanitizeNote(raw: String, maxLen: Int = NOTE_MAX): String =
        raw.filterNot { it.isISOControl() }.trim().take(maxLen)

    const val NOTE_MAX = 120

}
