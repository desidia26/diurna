package app.diurna.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Base64

/**
 * Estimates one entry with a single Messages API call. Structured output guarantees a reply that
 * matches [EstimatePrompt.SCHEMA]. Temporary trouble becomes [EstimateRetryable]; everything else
 * becomes [EstimateFailed] with a message from [EstimateErrors].
 *
 * Optional [baseUrl] overrides the Anthropic origin (SDK [AnthropicOkHttpClient.Builder.baseUrl]);
 * empty means the SDK default. Used for Anthropic-compatible gateways.
 */
class ClaudeFoodEstimator(
    private val apiKey: () -> String?,
    // A lookup-style estimate doesn't need a big model; Haiku is far cheaper and quick.
    override val model: String = "claude-haiku-5-5",
    private val baseUrl: () -> String? = { null },
    private val supportsImages: () -> Boolean = { true },
) : FoodEstimator {

    override suspend fun estimate(text: String, photo: ByteArray?): FoodEstimate = withContext(Dispatchers.IO) {
        val sendPhoto = photo.takeIf { supportsImages() }
        val estimate = EstimatePrompt.parse(
            ClaudeCall.json(apiKey, model, ClaudeRequest.params(model, text, sendPhoto), baseUrl = baseUrl)
        )
        if (photo != null && sendPhoto == null) {
            estimate.copy(note = VisionSupport.noteWithoutPhoto(estimate.note))
        } else {
            estimate
        }
    }
}

/** One structured-output Messages call with the app's error mapping; shared by food and cardio-screen reading. */
internal object ClaudeCall {
    suspend fun json(
        apiKey: () -> String?,
        model: String,
        params: MessageCreateParams,
        declined: String = "The model declined to estimate this entry",
        baseUrl: () -> String? = { null },
    ): String = withContext(Dispatchers.IO) {
        val key = apiKey() ?: throw NoApiKey()
        val builder = AnthropicOkHttpClient.builder().apiKey(key)
        baseUrl()?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val normalized = try {
                GatewayUrl.normalizeAnthropicBase(raw)
            } catch (e: GatewayUrlException) {
                throw EstimateFailed(e.message ?: "Invalid Anthropic base URL")
            }
            try {
                GatewayUrl.assertAllowed(normalized)
            } catch (e: GatewayUrlException) {
                throw EstimateFailed(e.message ?: "Invalid Anthropic base URL")
            }
            builder.baseUrl(normalized)
        }
        val client = builder.build()
        try {
            val response = client.messages().create(params)
            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                throw EstimateFailed(declined)
            }
            response.content().firstNotNullOfOrNull { it.text().orElse(null)?.text() }
                ?: throw EstimateFailed("The reply came back empty")
        } catch (e: EstimateFailed) {
            throw e
        } catch (e: AnthropicServiceException) {
            when (val o = EstimateErrors.classify(e.statusCode(), e.message, model)) {
                EstimateErrors.Retry -> throw EstimateRetryable(e)
                is EstimateErrors.Fail -> throw EstimateFailed(o.message)
            }
        } catch (e: AnthropicIoException) {
            throw EstimateRetryable(e) // offline, timeout, dropped connection
        } catch (e: IOException) {
            throw EstimateRetryable(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A bug or an unexpected response: show it rather than retry forever.
            throw EstimateFailed("Unexpected error (${e.javaClass.simpleName}). Tap Retry.")
        } finally {
            client.close()
        }
    }
}

/** Builds the Messages API request; separate from the call so tests can check what gets sent. */
internal object ClaudeRequest {
    fun params(
        model: String,
        text: String,
        photo: ByteArray? = null,
        system: String = EstimatePrompt.SYSTEM,
        schema: Map<String, Any> = EstimatePrompt.SCHEMA,
    ): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .apply {
                if (photo == null) {
                    addUserMessage(text)
                } else {
                    // Image before text, as Anthropic recommends; the text stays the main description.
                    addUserMessageOfBlockParams(
                        listOf(
                            ContentBlockParam.ofImage(
                                ImageBlockParam.builder()
                                    .source(
                                        Base64ImageSource.builder()
                                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                            .data(Base64.getEncoder().encodeToString(photo))
                                            .build()
                                    )
                                    .build()
                            ),
                            ContentBlockParam.ofText(TextBlockParam.builder().text(text).build()),
                        )
                    )
                }
            }
            .outputConfig(
                OutputConfig.builder()
                    // A quick lookup-style judgment; low effort keeps it fast and cheap.
                    .effort(OutputConfig.Effort.LOW)
                    .format(
                        JsonOutputFormat.builder()
                            .schema(JsonOutputFormat.Schema.builder().apply {
                                schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                            }.build())
                            .build()
                    )
                    .build()
            )
            .build()
}
