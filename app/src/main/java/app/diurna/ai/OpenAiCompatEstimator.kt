package app.diurna.ai

/**
 * Bring-your-own-gateway: any OpenAI-compatible `/v1/chat/completions` host (OpenRouter, LiteLLM,
 * Ollama, llama.cpp, LM Studio, Cloudflare AI Gateway, …). Reuses the hand-rolled transport from
 * [XaiChat] / [HttpXaiTransport]; key is optional for local servers.
 */
class OpenAiCompatFoodEstimator(
    private val apiKey: () -> String?,
    private val modelProvider: () -> String,
    private val transportProvider: () -> XaiTransport,
    private val supportsImages: () -> Boolean = { true },
    private val configured: () -> Boolean = { true },
) : FoodEstimator {

    override val model: String get() = modelProvider().ifBlank { "openai-compat" }
    override val gaveUpMessage: String get() = OpenAiCompatErrors.GAVE_UP

    override suspend fun estimate(text: String, photo: ByteArray?, detailed: Boolean, ingredients: Boolean): FoodEstimate {
        if (!configured()) throw NoApiKey(NO_CONFIG)
        val key = apiKey().orEmpty() // local servers often need none
        val sendPhoto = photo.takeIf { supportsImages() }
        val maxTok = if (detailed || ingredients) XaiChat.MAX_COMPLETION_TOKENS_DETAILED else XaiChat.MAX_COMPLETION_TOKENS
        val estimate = EstimatePrompt.parse(
            XaiChat.call(
                transportProvider(), key, model,
                XaiChat.request(
                    model, EstimatePrompt.system(detailed, ingredients), text, sendPhoto,
                    schema = EstimatePrompt.schema(detailed, ingredients),
                    maxCompletionTokens = maxTok,
                ),
                classify = OpenAiCompatErrors::classify,
            )
        )
        return if (photo != null && sendPhoto == null) {
            estimate.copy(note = VisionSupport.noteWithoutPhoto(estimate.note))
        } else {
            estimate
        }
    }

    override suspend fun guessBarcodeIngredients(ctx: BarcodeIngredientsContext): List<app.diurna.domain.FoodIngredient> {
        if (!configured()) throw NoApiKey(NO_CONFIG)
        val key = apiKey().orEmpty()
        val json = XaiChat.call(
            transportProvider(), key, model,
            XaiChat.request(
                model,
                BarcodeIngredientsPrompt.SYSTEM,
                BarcodeIngredientsPrompt.userMessage(ctx),
                photoJpeg = null,
                schemaName = "barcode_ingredients",
                schema = BarcodeIngredientsPrompt.SCHEMA,
                maxCompletionTokens = XaiChat.MAX_COMPLETION_TOKENS,
            ),
            classify = OpenAiCompatErrors::classify,
        )
        return BarcodeIngredientsPrompt.parse(json)
    }

    companion object {
        const val NO_CONFIG = "Set a base URL and model for OpenAI-compatible in Setup to estimate food"
    }
}

class OpenAiCompatCardioReader(
    private val apiKey: () -> String?,
    private val modelProvider: () -> String,
    private val transportProvider: () -> XaiTransport,
    private val supportsImages: () -> Boolean = { true },
    private val configured: () -> Boolean = { true },
) : CardioReader {
    override val model: String get() = modelProvider().ifBlank { "openai-compat" }

    override suspend fun read(photo: ByteArray): CardioReading {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        if (!configured()) throw NoApiKey(NO_CONFIG)
        val key = apiKey().orEmpty()
        val request = XaiChat.request(
            model, CardioPrompt.SYSTEM, CardioPrompt.USER_TEXT, photo,
            schemaName = "cardio_summary", schema = CardioPrompt.SCHEMA,
        )
        return CardioPrompt.parse(
            XaiChat.call(transportProvider(), key, model, request, classify = OpenAiCompatErrors::classify)
        )
    }

    companion object {
        const val NO_CONFIG = "Set a base URL and model for OpenAI-compatible in Setup to read photos"
    }
}



class OpenAiCompatLabelReader(
    private val apiKey: () -> String?,
    private val modelProvider: () -> String,
    private val transportProvider: () -> XaiTransport,
    private val supportsImages: () -> Boolean = { true },
    private val configured: () -> Boolean = { true },
) : LabelReader {
    override val model: String get() = modelProvider().ifBlank { "openai-compat" }

    override suspend fun read(photo: ByteArray): NutritionLabelReading {
        if (!supportsImages()) throw EstimateFailed(VisionSupport.PHOTO_FEATURES_DISABLED)
        if (!configured()) throw NoApiKey(NO_CONFIG)
        val key = apiKey().orEmpty()
        val request = XaiChat.request(
            model, LabelPrompt.SYSTEM, LabelPrompt.USER_TEXT, photo,
            schemaName = "nutrition_facts", schema = LabelPrompt.SCHEMA,
        )
        return LabelPrompt.parse(
            XaiChat.call(transportProvider(), key, model, request, classify = OpenAiCompatErrors::classify)
        )
    }

    companion object {
        const val NO_CONFIG = "Set a base URL and model for OpenAI-compatible in Setup to read labels"
    }
}

/**
 * Generic OpenAI-compatible error mapping. Raw API text is never shown. Vision-unsupported 400s
 * get a Setup hint via [VisionSupport].
 */
object OpenAiCompatErrors {
    const val BAD_KEY = EstimateErrors.BAD_KEY
    const val GAVE_UP = "Couldn't reach the gateway after several tries. Tap Retry."

    fun classify(status: Int, message: String?, model: String): EstimateErrors.Outcome {
        if (VisionSupport.isUnsupportedImageError(status, message)) {
            return EstimateErrors.Fail(VisionSupport.API_REJECTED_IMAGE)
        }
        val m = message.orEmpty().lowercase()
        return when {
            "spending limit" in m || "spend limit" in m || "usage limit" in m || "quota" in m ->
                EstimateErrors.Fail("Gateway usage limit reached. Check the provider console, then tap Retry.")
            "credit" in m || "billing" in m || status == 402 ->
                EstimateErrors.Fail("Gateway billing problem. Check the provider console, then tap Retry.")
            status == 401 -> EstimateErrors.Fail(BAD_KEY)
            "api key" in m && (status == 400 || status == 403) -> EstimateErrors.Fail(BAD_KEY)
            status == 403 -> EstimateErrors.Fail("This API key isn't allowed on this gateway.")
            status == 404 -> EstimateErrors.Fail("$model isn't available on this gateway.")
            status == 413 -> EstimateErrors.Fail("This entry is too long to estimate. Shorten it, then it will retry.")
            status == 400 || status == 422 -> EstimateErrors.Fail("The gateway couldn't process this entry. Try rewording it.")
            status == 408 || status == 409 || status == 429 || status >= 500 -> EstimateErrors.Retry
            else -> EstimateErrors.Fail("Unexpected error from the gateway (HTTP $status). Tap Retry.")
        }
    }
}
