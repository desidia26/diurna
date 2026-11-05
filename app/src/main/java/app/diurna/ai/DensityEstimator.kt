package app.diurna.ai

import app.diurna.domain.DensityLookup
import app.diurna.domain.DensityUnitKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Estimates density / grams-per-each when USDA portions are unavailable. */
fun interface DensityEstimator {
    suspend fun estimate(name: String, kind: DensityUnitKind): DensityLookup
}

class SelectedDensityEstimator(
    private val selected: () -> AiProvider,
    private val estimators: Map<AiProvider, DensityEstimator>,
) : DensityEstimator {
    override suspend fun estimate(name: String, kind: DensityUnitKind): DensityLookup {
        val est = estimators[selected()]
            ?: throw NoApiKey("No density estimator for the selected AI provider")
        return est.estimate(name, kind)
    }
}

class ClaudeDensityEstimator(
    private val apiKey: () -> String?,
    private val model: () -> String = { "claude-haiku-5-5" },
    private val baseUrl: () -> String? = { null },
) : DensityEstimator {
    override suspend fun estimate(name: String, kind: DensityUnitKind): DensityLookup = withContext(Dispatchers.IO) {
        if (apiKey() == null) throw NoApiKey("Add an Anthropic API key in Setup to estimate density")
        val m = model()
        val params = ClaudeRequest.params(
            m, DensityPrompt.userText(name, kind), photo = null,
            DensityPrompt.SYSTEM, DensityPrompt.SCHEMA,
        )
        DensityPrompt.parse(
            ClaudeCall.json(apiKey, m, params, declined = "The model declined to estimate density", baseUrl = baseUrl),
            kind,
        ).also { if (!it.hasValue) throw EstimateFailed("No density estimate") }
    }
}

class GrokDensityEstimator(
    private val apiKey: () -> String?,
    private val modelProvider: () -> String = { GrokFoodEstimator.DEFAULT_MODEL },
    private val transportProvider: () -> XaiTransport,
) : DensityEstimator {
    override suspend fun estimate(name: String, kind: DensityUnitKind): DensityLookup = withContext(Dispatchers.IO) {
        val key = apiKey() ?: throw NoApiKey("Add an xAI API key in Setup to estimate density")
        val model = modelProvider()
        val request = XaiChat.request(
            model, DensityPrompt.SYSTEM, DensityPrompt.userText(name, kind),
            photoJpeg = null,
            schemaName = "ingredient_density",
            schema = DensityPrompt.SCHEMA,
            maxCompletionTokens = 256,
        )
        DensityPrompt.parse(XaiChat.call(transportProvider(), key, model, request), kind)
            .also { if (!it.hasValue) throw EstimateFailed("No density estimate") }
    }
}

class OpenAiCompatDensityEstimator(
    private val apiKey: () -> String?,
    private val modelProvider: () -> String,
    private val transportProvider: () -> XaiTransport,
    private val configured: () -> Boolean = { true },
) : DensityEstimator {
    override suspend fun estimate(name: String, kind: DensityUnitKind): DensityLookup = withContext(Dispatchers.IO) {
        if (!configured()) throw EstimateFailed("Set a base URL and model in Setup")
        val key = apiKey().orEmpty()
        val model = modelProvider().trim().ifBlank { throw EstimateFailed("Set a model in Setup") }
        val request = XaiChat.request(
            model, DensityPrompt.SYSTEM, DensityPrompt.userText(name, kind),
            photoJpeg = null,
            schemaName = "ingredient_density",
            schema = DensityPrompt.SCHEMA,
            maxCompletionTokens = 256,
        )
        DensityPrompt.parse(
            XaiChat.call(transportProvider(), key, model, request, classify = OpenAiCompatErrors::classify),
            kind,
        ).also { if (!it.hasValue) throw EstimateFailed("No density estimate") }
    }
}
