package app.diurna.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-secret gateway settings (base URLs, model names, vision toggle). Lives in the same "secure"
 * prefs as the API keys so a restore starts clean along with no keys. Values aren't encrypted —
 * they aren't secrets — but they stay off backups and out of logs that might echo a URL.
 */
class GatewaySettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _snapshot = MutableStateFlow(read())
    val snapshot: StateFlow<GatewaySettings> = _snapshot.asStateFlow()

    fun get(): GatewaySettings = _snapshot.value

    fun update(transform: (GatewaySettings) -> GatewaySettings) {
        val next = transform(_snapshot.value)
        prefs.edit()
            .putString(KEY_OPENAI_BASE, next.openAiBaseUrl)
            .putString(KEY_OPENAI_MODEL, next.openAiModel)
            .putBoolean(KEY_OPENAI_VISION, next.openAiSupportsImages)
            .putString(KEY_ANTHROPIC_BASE, next.anthropicBaseUrl)
            .putBoolean(KEY_ANTHROPIC_VISION, next.anthropicSupportsImages)
            .putString(KEY_XAI_ENDPOINT, next.xaiEndpoint)
            .putBoolean(KEY_XAI_VISION, next.xaiSupportsImages)
            .apply()
        _snapshot.value = next
    }

    fun supportsImages(provider: AiProvider): Boolean = when (provider) {
        AiProvider.ANTHROPIC -> get().anthropicSupportsImages
        AiProvider.XAI -> get().xaiSupportsImages
        AiProvider.OPENAI_COMPAT -> get().openAiSupportsImages
    }

    fun openAiChatEndpoint(): String? {
        val base = get().openAiBaseUrl.trim()
        if (base.isEmpty()) return null
        return GatewayUrl.normalizeChatCompletions(base)
    }

    fun anthropicBaseOrNull(): String? =
        get().anthropicBaseUrl.trim().takeIf { it.isNotEmpty() }?.let { GatewayUrl.normalizeAnthropicBase(it) }

    fun xaiEndpointOrDefault(): String {
        val raw = get().xaiEndpoint.trim()
        if (raw.isEmpty()) return XaiChat.ENDPOINT
        return GatewayUrl.normalizeChatCompletions(raw)
    }

    private fun read() = GatewaySettings(
        openAiBaseUrl = prefs.getString(KEY_OPENAI_BASE, "").orEmpty(),
        openAiModel = prefs.getString(KEY_OPENAI_MODEL, "").orEmpty(),
        openAiSupportsImages = prefs.getBoolean(KEY_OPENAI_VISION, true),
        anthropicBaseUrl = prefs.getString(KEY_ANTHROPIC_BASE, "").orEmpty(),
        anthropicSupportsImages = prefs.getBoolean(KEY_ANTHROPIC_VISION, true),
        xaiEndpoint = prefs.getString(KEY_XAI_ENDPOINT, "").orEmpty(),
        xaiSupportsImages = prefs.getBoolean(KEY_XAI_VISION, true),
    )

    private companion object {
        const val PREFS = "secure"
        const val KEY_OPENAI_BASE = "openai_compat_base_url"
        const val KEY_OPENAI_MODEL = "openai_compat_model"
        const val KEY_OPENAI_VISION = "openai_compat_supports_images"
        const val KEY_ANTHROPIC_BASE = "anthropic_base_url"
        const val KEY_ANTHROPIC_VISION = "anthropic_supports_images"
        const val KEY_XAI_ENDPOINT = "xai_endpoint"
        const val KEY_XAI_VISION = "xai_supports_images"
    }
}

data class GatewaySettings(
    val openAiBaseUrl: String = "",
    val openAiModel: String = "",
    val openAiSupportsImages: Boolean = true,
    val anthropicBaseUrl: String = "",
    val anthropicSupportsImages: Boolean = true,
    val xaiEndpoint: String = "",
    val xaiSupportsImages: Boolean = true,
)
