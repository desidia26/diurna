package app.diurna.ai

import app.diurna.data.fdc.FdcNutritionProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Estimates per-100g nutrition when USDA FDC has no usable match. */
fun interface NutritionEstimator {
    suspend fun estimate(name: String): FdcNutritionProfile
}

class SelectedNutritionEstimator(
    private val selected: () -> AiProvider,
    private val estimators: Map<AiProvider, NutritionEstimator>,
) : NutritionEstimator {
    override suspend fun estimate(name: String): FdcNutritionProfile {
        val est = estimators[selected()]
            ?: throw NoApiKey("No nutrition estimator for the selected AI provider")
        return est.estimate(name)
    }
}

class ClaudeNutritionEstimator(
    private val apiKey: () -> String?,
    private val model: () -> String = { "claude-haiku-5-5" },
    private val baseUrl: () -> String? = { null },
) : NutritionEstimator {
    override suspend fun estimate(name: String): FdcNutritionProfile = withContext(Dispatchers.IO) {
        if (apiKey() == null) throw NoApiKey("Add an Anthropic API key in Setup to estimate nutrition")
        val m = model()
        val params = ClaudeRequest.params(
            m, IngredientNutritionPrompt.userText(name), photo = null,
            IngredientNutritionPrompt.SYSTEM, IngredientNutritionPrompt.SCHEMA,
        )
        IngredientNutritionPrompt.parse(
            ClaudeCall.json(apiKey, m, params, declined = "The model declined to estimate nutrition", baseUrl = baseUrl),
        )
    }
}

class GrokNutritionEstimator(
    private val apiKey: () -> String?,
    private val modelProvider: () -> String = { GrokFoodEstimator.DEFAULT_MODEL },
    private val transportProvider: () -> XaiTransport,
) : NutritionEstimator {
    override suspend fun estimate(name: String): FdcNutritionProfile = withContext(Dispatchers.IO) {
        val key = apiKey() ?: throw NoApiKey("Add an xAI API key in Setup to estimate nutrition")
        val model = modelProvider()
        val request = XaiChat.request(
            model, IngredientNutritionPrompt.SYSTEM, IngredientNutritionPrompt.userText(name),
            photoJpeg = null,
            schemaName = "ingredient_nutrition",
            schema = IngredientNutritionPrompt.SCHEMA,
            maxCompletionTokens = 256,
        )
        IngredientNutritionPrompt.parse(XaiChat.call(transportProvider(), key, model, request))
    }
}

class OpenAiCompatNutritionEstimator(
    private val apiKey: () -> String?,
    private val modelProvider: () -> String,
    private val transportProvider: () -> XaiTransport,
    private val configured: () -> Boolean = { true },
) : NutritionEstimator {
    override suspend fun estimate(name: String): FdcNutritionProfile = withContext(Dispatchers.IO) {
        if (!configured()) throw EstimateFailed("Set a base URL and model in Setup")
        val key = apiKey().orEmpty()
        val model = modelProvider().trim().ifBlank { throw EstimateFailed("Set a model in Setup") }
        val request = XaiChat.request(
            model, IngredientNutritionPrompt.SYSTEM, IngredientNutritionPrompt.userText(name),
            photoJpeg = null,
            schemaName = "ingredient_nutrition",
            schema = IngredientNutritionPrompt.SCHEMA,
            maxCompletionTokens = 256,
        )
        IngredientNutritionPrompt.parse(
            XaiChat.call(transportProvider(), key, model, request, classify = OpenAiCompatErrors::classify),
        )
    }
}
