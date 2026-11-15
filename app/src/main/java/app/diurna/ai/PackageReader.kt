package app.diurna.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads one photo of a food package: the Nutrition Facts panel (per serving, as printed) and the
 * ingredients list, whichever are visible. The barcode is decoded on the phone, not here.
 * Results are always shown for a check before saving.
 */
interface PackageReader {
    val model: String

    /** [photo]: a JPEG, already downscaled. Throws [NoApiKey], [EstimateFailed] or [EstimateRetryable]. */
    suspend fun read(photo: ByteArray): PackageReading
}

/**
 * [perServing] holds only values read from the panel, keyed by [PackagePrompt.NUTRIENT_KEYS]; a
 * missing key is unknown (never 0), and a printed 0 is kept as 0. Empty when no panel was read.
 */
data class PackageReading(
    val hasPanel: Boolean,
    val productName: String,
    val brand: String,
    val servingText: String,
    /** Grams in one serving as printed, or null (e.g. a volume-only serving). */
    val servingGrams: Double?,
    val servingsPerContainer: Double?,
    val perServing: Map<String, Double>,
    /** The ingredients list as printed, or null when none was visible. */
    val ingredientsText: String?,
    val note: String = "",
)

object PackagePrompt {
    /** Upper bounds per serving; above them a value is a misread, so it becomes unknown. */
    private val MAX: Map<String, Double> = linkedMapOf(
        "kcal" to 3_000.0,
        "protein_g" to 300.0, "carbs_g" to 500.0, "fat_g" to 300.0,
        "fiber_g" to 200.0, "sugars_g" to 500.0, "added_sugars_g" to 500.0,
        "sat_fat_g" to 300.0, "trans_fat_g" to 300.0, "mono_fat_g" to 300.0, "poly_fat_g" to 300.0,
        "cholesterol_mg" to 5_000.0, "sodium_mg" to 20_000.0, "potassium_mg" to 20_000.0,
        "calcium_mg" to 10_000.0, "iron_mg" to 500.0, "vitamin_d_ug" to 1_000.0,
    )
    val NUTRIENT_KEYS: List<String> = MAX.keys.toList()
    const val MAX_INGREDIENTS = 2_000

    const val SYSTEM = """You read one photo of a packaged food: its US Nutrition Facts panel and its ingredients list, whichever are visible. The photo may be rotated, glossy, curved or partly cut off.

Rules:
- has_nutrition_panel: true only if a Nutrition Facts panel is visible and at least Calories is readable.
- Report panel values for ONE serving exactly as printed (the "Amount per serving" column; if there are two columns, use the per-serving one, not per container).
- Use -1 for any number that is not printed or that you cannot read with confidence. Never guess, estimate or compute a value. A value printed as 0 (e.g. "Trans Fat 0g") is 0, not -1.
- Ignore % Daily Value numbers; report amounts only. "Potas." means potassium; "Vit. D" means vitamin D in micrograms (mcg = µg).
- serving_text: the Serving size line as printed, under 60 characters. serving_grams: the gram weight printed in it (e.g. "1 oz (28g)" → 28), else -1. Do not convert cups, tablespoons or millilitres to grams.
- servings_per_container: the number printed (use the number in "about 12"), else -1.
- product_name and brand: as printed on the package if visible, else "".
- ingredients_text: the ingredients list exactly as printed after "Ingredients:" (keep the order and parentheses), else "". Do not include allergen statements ("Contains:", "May contain") or recipes printed on the package.
- note: anything important about readability, under 60 characters, or "".
- If the photo shows no Nutrition Facts panel and no ingredients list, set has_nutrition_panel false, every number -1, and ingredients_text ""."""

    const val USER_TEXT = "Read this food package."

    private val NUM = mapOf("type" to "number")
    private val STR = mapOf("type" to "string")

    val SCHEMA: Map<String, Any> = run {
        val props = linkedMapOf<String, Any>(
            "has_nutrition_panel" to mapOf("type" to "boolean"),
            "product_name" to STR,
            "brand" to STR,
            "serving_text" to STR,
            "serving_grams" to NUM,
            "servings_per_container" to NUM,
        )
        NUTRIENT_KEYS.forEach { props[it] = NUM }
        props["ingredients_text"] = STR
        props["note"] = STR
        mapOf(
            "type" to "object",
            "properties" to props,
            "required" to props.keys.toList(),
            "additionalProperties" to false,
        )
    }

    private val mapper = ObjectMapper()

    fun parse(json: String): PackageReading {
        val node: JsonNode = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject }
            ?: throw EstimateFailed("Couldn't read the label")
        fun num(name: String, max: Double): Double? {
            val n = node.get(name) ?: return null
            if (!n.isNumber) return null
            val v = n.asDouble()
            return v.takeIf { it.isFinite() && it >= 0 && it <= max }
        }
        fun text(name: String, max: Int): String =
            node.get(name)?.takeIf { it.isTextual }?.asText().orEmpty()
                .map { if (it.isISOControl()) ' ' else it }
                .filter { Character.getType(it) != Character.FORMAT.toInt() }
                .joinToString("").replace(Regex("\\s+"), " ").trim().take(max).trim()
        // Every numeric field is required by the schema; a non-number (e.g. "two hundred") is a broken reply.
        if (node.get("kcal")?.isNumber != true) throw EstimateFailed("Couldn't read the label")

        val hasPanel = node.get("has_nutrition_panel")?.asBoolean(false) == true
        val perServing = if (!hasPanel) emptyMap() else buildMap {
            MAX.forEach { (key, max) -> num(key, max)?.let { put(key, it) } }
        }
        val servingText = text("serving_text", 60)
        val servingGrams = num("serving_grams", 2_000.0)?.takeIf { it > 0 } ?: gramsIn(servingText)
        val ingredients = text("ingredients_text", MAX_INGREDIENTS).ifEmpty { null }
        if (perServing.isEmpty() && ingredients == null) {
            val note = text("note", 60)
            throw EstimateFailed(
                if (note.isNotEmpty()) "No label or ingredients found ($note)." else "No label or ingredients found.",
            )
        }
        return PackageReading(
            hasPanel = hasPanel && perServing.isNotEmpty(),
            productName = text("product_name", 80),
            brand = text("brand", 80),
            servingText = servingText,
            servingGrams = servingGrams,
            servingsPerContainer = num("servings_per_container", 10_000.0)?.takeIf { it > 0 },
            perServing = perServing,
            ingredientsText = ingredients,
            note = text("note", 80),
        )
    }

    private fun gramsIn(text: String): Double? =
        Regex("""(\d+(?:\.\d+)?)\s*g\b""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
            ?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 2_000 }
}

class ClaudePackageReader(
    private val apiKey: () -> String?,
    override val model: String = "claude-haiku-5-5",
    private val baseUrl: () -> String? = { null },
    private val supportsImages: () -> Boolean = { true },
) : PackageReader {
    override suspend fun read(photo: ByteArray): PackageReading = withContext(Dispatchers.IO) {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        if (apiKey() == null) throw NoApiKey("Add an Anthropic API key in Setup to read labels")
        val params = ClaudeRequest.params(model, PackagePrompt.USER_TEXT, photo, PackagePrompt.SYSTEM, PackagePrompt.SCHEMA)
        PackagePrompt.parse(ClaudeCall.json(apiKey, model, params, declined = "The model declined to read this label", baseUrl = baseUrl))
    }
}

class GrokPackageReader(
    private val apiKey: () -> String?,
    override val model: String = GrokFoodEstimator.DEFAULT_MODEL,
    private val transport: XaiTransport = HttpXaiTransport(),
    private val supportsImages: () -> Boolean = { true },
) : PackageReader {
    override suspend fun read(photo: ByteArray): PackageReading {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        val key = apiKey() ?: throw NoApiKey(GrokLabelReader.NO_KEY)
        val request = XaiChat.request(
            model, PackagePrompt.SYSTEM, PackagePrompt.USER_TEXT, photo,
            schemaName = "food_package", schema = PackagePrompt.SCHEMA,
        )
        return PackagePrompt.parse(XaiChat.call(transport, key, model, request))
    }
}

class OpenAiCompatPackageReader(
    private val apiKey: () -> String?,
    private val modelProvider: () -> String,
    private val transportProvider: () -> XaiTransport,
    private val supportsImages: () -> Boolean = { true },
    private val configured: () -> Boolean = { true },
) : PackageReader {
    override val model: String get() = modelProvider().ifBlank { "openai-compat" }

    override suspend fun read(photo: ByteArray): PackageReading {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        if (!configured()) throw NoApiKey(OpenAiCompatLabelReader.NO_CONFIG)
        val request = XaiChat.request(
            model, PackagePrompt.SYSTEM, PackagePrompt.USER_TEXT, photo,
            schemaName = "food_package", schema = PackagePrompt.SCHEMA,
        )
        return PackagePrompt.parse(
            XaiChat.call(transportProvider(), apiKey().orEmpty(), model, request, classify = OpenAiCompatErrors::classify),
        )
    }
}

/** Uses whichever AI provider is selected in Setup, like [SelectedLabelReader]. */
class SelectedPackageReader(
    private val selected: () -> AiProvider,
    private val byProvider: Map<AiProvider, PackageReader>,
) : PackageReader {
    override val model: String get() = byProvider.getValue(selected()).model
    override suspend fun read(photo: ByteArray): PackageReading = byProvider.getValue(selected()).read(photo)
}
