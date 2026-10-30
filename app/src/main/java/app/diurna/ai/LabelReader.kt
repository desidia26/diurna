package app.diurna.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a US Nutrition Facts label from a photo so a meal form can be filled without typing.
 * Numbers are per serving as printed. The person always checks them before saving.
 */
interface LabelReader {
    val model: String

    /** [photo]: a JPEG, already downscaled. Throws [NoApiKey], [EstimateFailed] or [EstimateRetryable]. */
    suspend fun read(photo: ByteArray): NutritionLabelReading
}

/**
 * Per-serving values from the label. [kcal]/[proteinG]/[carbsG]/[fatG] are always set when the
 * label was readable; detailed fields are null when the panel didn't show them.
 */
data class NutritionLabelReading(
    val productName: String,
    val serving: String,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int? = null,
    val sugarsG: Int? = null,
    val addedSugarsG: Int? = null,
    val satFatG: Int? = null,
    val transFatG: Int? = null,
    val cholesterolMg: Int? = null,
    /** Short note about anything unclear (e.g. "blurry sugars line"). */
    val note: String = "",
)

object LabelPrompt {
    const val SYSTEM = """You read a US Nutrition Facts label from a photo (FDA panel on packaged food).

Report the values for ONE serving exactly as printed:
- product_name: the product name if visible on the package near the panel, else "".
- serving: the Serving Size line as printed (e.g. "2/3 cup (55g)", "1 bottle (355 mL)"), under 60 characters.
- kcal: Calories (Cal) as an integer.
- protein_g, carbs_g, fat_g: Total Protein, Total Carbohydrate, Total Fat in grams (integers; round 0.5 up).
- fiber_g: Dietary Fiber in grams, or 0 if not shown.
- sugars_g: Total Sugars in grams, or 0 if not shown.
- added_sugars_g: Includes Added Sugars in grams, or 0 if not shown.
- sat_fat_g: Saturated Fat in grams, or 0 if not shown.
- trans_fat_g: Trans Fat in grams, or 0 if not shown.
- cholesterol_mg: Cholesterol in milligrams, or 0 if not shown.
- note: anything important about readability under 60 characters, or "".

Use 0 for any number you can't read clearly; never invent a value. Ignore % Daily Value, vitamins, minerals and sodium. If the photo is not a Nutrition Facts label, return zeros and note "not a nutrition label"."""

    const val USER_TEXT = "Read this Nutrition Facts label."

    private val INT = mapOf("type" to "integer")
    private val STR = mapOf("type" to "string")

    val SCHEMA: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "product_name" to STR,
            "serving" to STR,
            "kcal" to INT,
            "protein_g" to INT,
            "carbs_g" to INT,
            "fat_g" to INT,
            "fiber_g" to INT,
            "sugars_g" to INT,
            "added_sugars_g" to INT,
            "sat_fat_g" to INT,
            "trans_fat_g" to INT,
            "cholesterol_mg" to INT,
            "note" to STR,
        ),
        "required" to listOf(
            "product_name", "serving", "kcal", "protein_g", "carbs_g", "fat_g",
            "fiber_g", "sugars_g", "added_sugars_g", "sat_fat_g", "trans_fat_g", "cholesterol_mg", "note",
        ),
        "additionalProperties" to false,
    )

    private val mapper = ObjectMapper()

    /**
     * Zero / missing detailed values become null. A reading with no calories fails so the person
     * types by hand instead of saving an empty form.
     */
    fun parse(json: String): NutritionLabelReading {
        val node: JsonNode = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject }
            ?: throw EstimateFailed("Couldn't read the label")
        fun int(name: String): Int? = node.get(name)?.takeIf { it.isNumber }?.let {
            val v = it.asDouble()
            if (!v.isFinite() || v < 0 || v > Int.MAX_VALUE.toDouble()) null
            else Math.round(v).toInt()
        }
        fun text(name: String): String = node.get(name)?.takeIf { it.isTextual }?.asText().orEmpty().trim()
        // FDA panels almost always print these; keep 0 as 0 (distinct from null = unread/absent).
        fun shown(name: String): Int? = int(name)?.takeIf { it >= 0 }
        val kcal = int("kcal")?.takeIf { it > 0 }
            ?: throw EstimateFailed(
                text("note").takeIf { it.isNotEmpty() }?.let { "No calories found ($it). Enter it by hand." }
                    ?: "No calories found. Enter it by hand.",
            )
        return NutritionLabelReading(
            productName = text("product_name").take(80),
            serving = text("serving").take(60),
            kcal = kcal,
            proteinG = int("protein_g")?.coerceAtLeast(0) ?: 0,
            carbsG = int("carbs_g")?.coerceAtLeast(0) ?: 0,
            fatG = int("fat_g")?.coerceAtLeast(0) ?: 0,
            fiberG = shown("fiber_g"),
            sugarsG = shown("sugars_g"),
            addedSugarsG = shown("added_sugars_g"),
            satFatG = shown("sat_fat_g"),
            transFatG = shown("trans_fat_g"),
            cholesterolMg = shown("cholesterol_mg"),
            note = text("note").take(80),
        )
    }
}

class ClaudeLabelReader(
    private val apiKey: () -> String?,
    // Printed label OCR is easy for a small vision model, same as cardio screens.
    override val model: String = "claude-haiku-5-5",
    private val baseUrl: () -> String? = { null },
    private val supportsImages: () -> Boolean = { true },
) : LabelReader {
    override suspend fun read(photo: ByteArray): NutritionLabelReading = withContext(Dispatchers.IO) {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        if (apiKey() == null) throw NoApiKey("Add an Anthropic API key in Setup to read labels")
        val params = ClaudeRequest.params(model, LabelPrompt.USER_TEXT, photo, LabelPrompt.SYSTEM, LabelPrompt.SCHEMA)
        LabelPrompt.parse(ClaudeCall.json(apiKey, model, params, declined = "The model declined to read this label", baseUrl = baseUrl))
    }
}

class GrokLabelReader(
    private val apiKey: () -> String?,
    override val model: String = GrokFoodEstimator.DEFAULT_MODEL,
    private val transport: XaiTransport = HttpXaiTransport(),
    private val supportsImages: () -> Boolean = { true },
) : LabelReader {
    override suspend fun read(photo: ByteArray): NutritionLabelReading {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        val key = apiKey() ?: throw NoApiKey(NO_KEY)
        val request = XaiChat.request(
            model, LabelPrompt.SYSTEM, LabelPrompt.USER_TEXT, photo,
            schemaName = "nutrition_facts", schema = LabelPrompt.SCHEMA,
        )
        return LabelPrompt.parse(XaiChat.call(transport, key, model, request))
    }

    companion object {
        const val NO_KEY = "Add an xAI (Grok) API key in Setup to read labels"
    }
}

/** Uses whichever AI provider is selected in Setup, like [SelectedCardioReader]. */
class SelectedLabelReader(
    private val selected: () -> AiProvider,
    private val byProvider: Map<AiProvider, LabelReader>,
) : LabelReader {
    override val model: String get() = byProvider.getValue(selected()).model
    override suspend fun read(photo: ByteArray): NutritionLabelReading = byProvider.getValue(selected()).read(photo)
}
