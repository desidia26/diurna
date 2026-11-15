package app.diurna.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads one photo that may show several food packages: per product its Nutrition Facts panel
 * (per serving, as printed), ingredients list and front-of-package identity, plus questions when
 * the photo is ambiguous. Barcodes are decoded on the phone, not here. Results are always shown
 * for a check before saving.
 */
interface PackageReader {
    val model: String

    /** [photo]: a JPEG, already downscaled. Throws [NoApiKey], [EstimateFailed] or [EstimateRetryable]. */
    suspend fun read(photo: ByteArray): PackagePhotoReading

    /**
     * A new interpretation from [previous] plus the person's [answer]. [photo] only when it is
     * still in memory for this session; otherwise the refinement works from the stored text.
     */
    suspend fun refine(photo: ByteArray?, previous: PackagePhotoReading, answer: String): PackagePhotoReading
}

/** One AI interpretation of a photo: products in left-to-right order, and its questions. */
data class PackagePhotoReading(
    val products: List<PackageReading>,
    val questions: List<PackageQuestion> = emptyList(),
    val note: String = "",
)

/**
 * A question the AI asks when the photo is ambiguous. [productIndex] is the product it's about,
 * or null for the whole photo; [topic] is one of [PackagePrompt.TOPICS].
 */
data class PackageQuestion(
    val productIndex: Int?,
    val topic: String,
    val text: String,
    val suggestions: List<String> = emptyList(),
)

/**
 * [perServing] holds only values read from the panel, keyed by [PackagePrompt.NUTRIENT_KEYS]; a
 * missing key is unknown (never 0), and a printed 0 is kept as 0. Empty when no panel was read.
 * [productName], [brand] and [sizeText] identify the product from its front; an identity alone
 * never becomes nutrient values (the app needs a matched database record for that).
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
    /** Net weight / size as printed on the front, e.g. "NET WT 16 OZ (454g)". */
    val sizeText: String = "",
    /** Digits printed under this product's barcode (8–14 digits), when readable. */
    val printedBarcode: String? = null,
    /** Horizontal centre of this product in the photo, 0 (left) to 1 (right). */
    val centerX: Double? = null,
) {
    val hasIdentity: Boolean get() = productName.isNotBlank() || brand.isNotBlank()
}

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
    const val MAX_PRODUCTS = 8
    const val MAX_QUESTIONS = 4
    const val MAX_ANSWER = 500
    val TOPICS = listOf("serving_basis", "identity", "digits", "mapping", "other")

    const val SYSTEM = """You read one photo of packaged food. It may show ONE package or SEVERAL packages side by side. For each distinct package, report its US Nutrition Facts panel, its ingredients list and its identity from the front, whichever are visible. The photo may be rotated, glossy, curved or partly cut off.

Products:
- One entry in "products" per distinct package, ordered left to right as they appear. Never merge two packages into one entry; never split one package into two. A package with nothing readable at all is left out.
- center_x: the horizontal centre of that package in the photo, 0 = left edge, 1 = right edge; -1 if unsure.
- product_name, brand, size_text: as printed on the package front or label (size_text is the net weight or size line, e.g. "NET WT 16 OZ (454g)"), else "". Do not guess a product you cannot read.
- printed_barcode: the digits printed under that package's barcode, digits only, if every digit is clearly readable, else "".
- has_nutrition_panel: true only if that package's Nutrition Facts panel is visible and at least Calories is readable.
- Report panel values for ONE serving exactly as printed (the "Amount per serving" column; if there are two columns, use the per-serving one, not per container).
- Use -1 for any number that is not printed or that you cannot read with confidence. Never guess, estimate or compute a value. A value printed as 0 (e.g. "Trans Fat 0g") is 0, not -1.
- Ignore % Daily Value numbers; report amounts only. "Potas." means potassium; "Vit. D" means vitamin D in micrograms (mcg = µg).
- serving_text: the Serving size line as printed, under 60 characters. serving_grams: the gram weight printed in it (e.g. "1 oz (28g)" → 28), else -1. Do not convert cups, tablespoons or millilitres to grams.
- servings_per_container: the number printed (use the number in "about 12"), else -1.
- ingredients_text: the ingredients list exactly as printed after "Ingredients:" (keep the order and parentheses), else "". Do not include allergen statements ("Contains:", "May contain") or recipes printed on the package.
- note: anything important about readability, under 60 characters, or "".

Questions:
- Ask a question ONLY when a real ambiguity would change what gets saved, and you cannot resolve it from the photo. Examples: two per-serving columns that are both plausible for the same serving (e.g. "Dry" vs "As prepared"), a serving size whose basis is unclear, two plausible products, barcode digits you can only partly read, or several packages where it is unclear which panel belongs to which package.
- A clear, single-column label asks NO questions: return an empty "questions" list.
- Each question: product_index (the 0-based index into products, or -1 for the whole photo), topic (serving_basis, identity, digits, mapping or other), text (one short question, under 160 characters) and suggested_answers (up to 4 short answers, or an empty list).
- When there is an ambiguity, still fill in your best reading using the first plausible option, and say which one you used in the question text.

If the photo shows no package at all, return an empty products list and no questions."""

    const val USER_TEXT = "Read the food packages in this photo."

    private val NUM = mapOf("type" to "number")
    private val STR = mapOf("type" to "string")

    private fun strict(props: Map<String, Any>) = mapOf(
        "type" to "object",
        "properties" to props,
        "required" to props.keys.toList(),
        "additionalProperties" to false,
    )

    val SCHEMA: Map<String, Any> = run {
        val product = linkedMapOf<String, Any>(
            "has_nutrition_panel" to mapOf("type" to "boolean"),
            "product_name" to STR,
            "brand" to STR,
            "size_text" to STR,
            "printed_barcode" to STR,
            "center_x" to NUM,
            "serving_text" to STR,
            "serving_grams" to NUM,
            "servings_per_container" to NUM,
        )
        NUTRIENT_KEYS.forEach { product[it] = NUM }
        product["ingredients_text"] = STR
        product["note"] = STR
        val question = linkedMapOf<String, Any>(
            "product_index" to mapOf("type" to "integer"),
            "topic" to mapOf("type" to "string", "enum" to TOPICS),
            "text" to STR,
            "suggested_answers" to mapOf("type" to "array", "items" to STR),
        )
        strict(linkedMapOf(
            "products" to mapOf("type" to "array", "items" to strict(product)),
            "questions" to mapOf("type" to "array", "items" to strict(question)),
            "note" to STR,
        ))
    }

    /**
     * The user turn for a refinement. The previous reading and the answer are quoted as data;
     * the answer is the person's own words, capped and stripped of control characters.
     */
    fun refineText(previous: PackagePhotoReading, answer: String, hasPhoto: Boolean): String {
        val a = clean(answer, MAX_ANSWER)
        return buildString {
            append(if (hasPhoto) "Here is the same photo again. " else "There is no photo this time; work only from the previous reading. ")
            append("Your previous reading of it, as JSON:\n")
            append(toJson(previous))
            append("\n\nThe person answered or corrected (their words, between the markers):\n<<<\n")
            append(a)
            append("\n>>>\nReturn the complete corrected reading in the same format. Change only what the answer affects and keep everything else exactly as before. ")
            append("Never invent numbers that are not on the label")
            append(if (hasPhoto) "" else " or in the previous reading, except where the person's answer states them")
            append(". Drop questions the answer resolved; ask a new one only if a real ambiguity remains.")
        }
    }

    private val mapper = ObjectMapper()

    private fun clean(raw: String, max: Int): String = raw
        .map { if (it.isISOControl() && it != '\n') ' ' else it }
        .filter { Character.getType(it) != Character.FORMAT.toInt() }
        .joinToString("").replace(Regex("[ \t]+"), " ").trim().take(max).trim()

    private fun failed(): Nothing = throw EstimateFailed("Couldn't read the label")

    fun parse(json: String): PackagePhotoReading {
        val root: JsonNode = runCatching { mapper.readTree(json) }.getOrNull()?.takeIf { it.isObject } ?: failed()
        val list = root.get("products")?.takeIf { it.isArray } ?: failed()
        val products = list.take(MAX_PRODUCTS * 2).mapNotNull { parseProduct(it) }.take(MAX_PRODUCTS)
        val questions = (root.get("questions")?.takeIf { it.isArray }?.toList().orEmpty())
            .mapNotNull { parseQuestion(it, products.size) }.take(MAX_QUESTIONS)
        val note = text(root, "note", 60)
        if (products.isEmpty() && questions.isEmpty()) {
            throw EstimateFailed(if (note.isNotEmpty()) "No label or ingredients found ($note)." else "No label or ingredients found.")
        }
        return PackagePhotoReading(products, questions, note)
    }

    private fun text(node: JsonNode, name: String, max: Int): String =
        node.get(name)?.takeIf { it.isTextual }?.asText().orEmpty()
            .map { if (it.isISOControl()) ' ' else it }
            .filter { Character.getType(it) != Character.FORMAT.toInt() }
            .joinToString("").replace(Regex("\\s+"), " ").trim().take(max).trim()

    private fun parseProduct(node: JsonNode): PackageReading? {
        if (!node.isObject) failed()
        fun num(name: String, max: Double): Double? {
            val n = node.get(name) ?: return null
            if (!n.isNumber) return null
            val v = n.asDouble()
            return v.takeIf { it.isFinite() && it >= 0 && it <= max }
        }
        // Every numeric field is required by the schema; a non-number (e.g. "two hundred") is a broken reply.
        if (node.get("kcal")?.isNumber != true) failed()

        val hasPanel = node.get("has_nutrition_panel")?.asBoolean(false) == true
        val perServing = if (!hasPanel) emptyMap() else buildMap {
            MAX.forEach { (key, max) -> num(key, max)?.let { put(key, it) } }
        }
        val servingText = text(node, "serving_text", 60)
        val servingGrams = num("serving_grams", 2_000.0)?.takeIf { it > 0 } ?: gramsIn(servingText)
        val ingredients = text(node, "ingredients_text", MAX_INGREDIENTS).ifEmpty { null }
        val printed = text(node, "printed_barcode", 40).filter { it.isDigit() }.takeIf { it.length in 8..14 }
        val r = PackageReading(
            hasPanel = hasPanel && perServing.isNotEmpty(),
            productName = text(node, "product_name", 80),
            brand = text(node, "brand", 80),
            servingText = servingText,
            servingGrams = servingGrams,
            servingsPerContainer = num("servings_per_container", 10_000.0)?.takeIf { it > 0 },
            perServing = perServing,
            ingredientsText = ingredients,
            note = text(node, "note", 60),
            sizeText = text(node, "size_text", 60),
            printedBarcode = printed,
            centerX = num("center_x", 1.0),
        )
        return r.takeIf { it.perServing.isNotEmpty() || it.ingredientsText != null || it.hasIdentity }
    }

    private fun parseQuestion(node: JsonNode, products: Int): PackageQuestion? {
        if (!node.isObject) return null
        val t = text(node, "text", 200).ifEmpty { return null }
        val idx = node.get("product_index")?.takeIf { it.isIntegralNumber }?.asInt()?.takeIf { it in 0 until products }
        val topic = text(node, "topic", 20).takeIf { it in TOPICS } ?: "other"
        val suggestions = node.get("suggested_answers")?.takeIf { it.isArray }?.toList().orEmpty()
            .mapNotNull { s -> s.takeIf { it.isTextual }?.asText()?.let { clean(it, 80) }?.ifEmpty { null } }.distinct().take(4)
        return PackageQuestion(idx, topic, t, suggestions)
    }

    /** The stored / re-sent form of an interpretation: the reply schema, unknowns as -1. */
    fun toJson(r: PackagePhotoReading): String {
        val f = JsonNodeFactory.instance
        val root = f.objectNode()
        val products = root.putArray("products")
        r.products.forEach { p ->
            val o: ObjectNode = products.addObject()
            o.put("has_nutrition_panel", p.hasPanel)
            o.put("product_name", p.productName)
            o.put("brand", p.brand)
            o.put("size_text", p.sizeText)
            o.put("printed_barcode", p.printedBarcode.orEmpty())
            o.put("center_x", p.centerX ?: -1.0)
            o.put("serving_text", p.servingText)
            o.put("serving_grams", p.servingGrams ?: -1.0)
            o.put("servings_per_container", p.servingsPerContainer ?: -1.0)
            NUTRIENT_KEYS.forEach { k -> o.put(k, p.perServing[k] ?: -1.0) }
            o.put("ingredients_text", p.ingredientsText.orEmpty())
            o.put("note", p.note)
        }
        val qs = root.putArray("questions")
        r.questions.forEach { q ->
            val o = qs.addObject()
            o.put("product_index", q.productIndex ?: -1)
            o.put("topic", q.topic)
            o.put("text", q.text)
            val a = o.putArray("suggested_answers")
            q.suggestions.forEach { a.add(it) }
        }
        root.put("note", r.note)
        return mapper.writeValueAsString(root)
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
    override suspend fun read(photo: ByteArray): PackagePhotoReading = withContext(Dispatchers.IO) {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        if (apiKey() == null) throw NoApiKey("Add an Anthropic API key in Setup to read labels")
        call(PackagePrompt.USER_TEXT, photo)
    }

    override suspend fun refine(photo: ByteArray?, previous: PackagePhotoReading, answer: String): PackagePhotoReading = withContext(Dispatchers.IO) {
        if (photo != null && !supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        if (apiKey() == null) throw NoApiKey("Add an Anthropic API key in Setup to read labels")
        call(PackagePrompt.refineText(previous, answer, photo != null), photo)
    }

    private suspend fun call(text: String, photo: ByteArray?): PackagePhotoReading {
        val params = ClaudeRequest.params(model, text, photo, PackagePrompt.SYSTEM, PackagePrompt.SCHEMA)
        return PackagePrompt.parse(ClaudeCall.json(apiKey, model, params, declined = "The model declined to read this label", baseUrl = baseUrl))
    }
}

class GrokPackageReader(
    private val apiKey: () -> String?,
    override val model: String = GrokFoodEstimator.DEFAULT_MODEL,
    private val transport: XaiTransport = HttpXaiTransport(),
    private val supportsImages: () -> Boolean = { true },
) : PackageReader {
    override suspend fun read(photo: ByteArray): PackagePhotoReading {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        return call(PackagePrompt.USER_TEXT, photo)
    }

    override suspend fun refine(photo: ByteArray?, previous: PackagePhotoReading, answer: String): PackagePhotoReading {
        if (photo != null && !supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        return call(PackagePrompt.refineText(previous, answer, photo != null), photo)
    }

    private suspend fun call(text: String, photo: ByteArray?): PackagePhotoReading {
        val key = apiKey() ?: throw NoApiKey(GrokLabelReader.NO_KEY)
        val request = XaiChat.request(
            model, PackagePrompt.SYSTEM, text, photo,
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

    override suspend fun read(photo: ByteArray): PackagePhotoReading {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        return call(PackagePrompt.USER_TEXT, photo)
    }

    override suspend fun refine(photo: ByteArray?, previous: PackagePhotoReading, answer: String): PackagePhotoReading {
        if (photo != null && !supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        return call(PackagePrompt.refineText(previous, answer, photo != null), photo)
    }

    private suspend fun call(text: String, photo: ByteArray?): PackagePhotoReading {
        if (!configured()) throw NoApiKey(OpenAiCompatLabelReader.NO_CONFIG)
        val request = XaiChat.request(
            model, PackagePrompt.SYSTEM, text, photo,
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
    override suspend fun read(photo: ByteArray): PackagePhotoReading = byProvider.getValue(selected()).read(photo)
    override suspend fun refine(photo: ByteArray?, previous: PackagePhotoReading, answer: String): PackagePhotoReading =
        byProvider.getValue(selected()).refine(photo, previous, answer)
}
