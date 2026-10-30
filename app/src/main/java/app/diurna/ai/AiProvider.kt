package app.diurna.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which AI service food estimates go to. Each has its own key; switching keeps both, so going
 * back doesn't mean re-entering one. Anthropic is the default, so a phone that only ever had a
 * Claude key behaves exactly as before. OpenAI-compatible is bring-your-own-gateway: a base URL,
 * model name, and optional key for any /v1/chat/completions endpoint.
 */
enum class AiProvider(
    /** Stored in prefs; never rename. */
    val id: String,
    val label: String,
    val company: String,
    val keyPlaceholder: String,
    /** Local / gateway servers often need no key. */
    val keyOptional: Boolean = false,
) {
    ANTHROPIC("anthropic", "Claude", "Anthropic", "sk-ant-…"),
    XAI("xai", "Grok", "xAI", "xai-…"),
    OPENAI_COMPAT("openai_compat", "OpenAI-compatible", "OpenAI-compatible", "sk-… (optional)", keyOptional = true);

    companion object {
        val DEFAULT = ANTHROPIC
        fun fromId(id: String?): AiProvider = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * The chosen [AiProvider]. Lives next to the encrypted keys in the "secure" prefs, which are
 * left out of backups, so a restored phone starts back on the default along with no keys.
 */
class AiProviderStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private val _selected = MutableStateFlow(AiProvider.fromId(prefs.getString(KEY, null)))
    val selected: StateFlow<AiProvider> = _selected

    fun get(): AiProvider = _selected.value

    fun set(provider: AiProvider) {
        prefs.edit().putString(KEY, provider.id).apply()
        _selected.value = provider
    }

    private companion object {
        const val KEY = "ai_provider"
    }
}

/** Sends each estimate to whichever provider is selected at the time of the call. */
class SelectedFoodEstimator(
    private val selected: () -> AiProvider,
    private val byProvider: Map<AiProvider, FoodEstimator>,
) : FoodEstimator {
    private fun current(): FoodEstimator = byProvider.getValue(selected())

    override val model: String get() = current().model
    override val gaveUpMessage: String get() = current().gaveUpMessage

    override suspend fun estimate(text: String, photo: ByteArray?, detailed: Boolean, ingredients: Boolean): FoodEstimate {
        val estimator = current()
        // Recorded with the estimate, so switching provider mid-call can't mislabel it.
        return estimator.estimate(text, photo, detailed, ingredients)
            .let { it.copy(model = it.model ?: estimator.model) }
    }
}
