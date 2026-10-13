package app.daybook.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Estimates one entry with a single Messages API call. Structured output guarantees a reply that
 * matches [EstimatePrompt.SCHEMA]. Temporary trouble becomes [EstimateRetryable]; everything else
 * becomes [EstimateFailed] with a message from [EstimateErrors].
 */
class ClaudeFoodEstimator(
    private val apiKey: () -> String?,
    // A lookup-style estimate doesn't need a big model; Haiku is far cheaper and quick.
    override val model: String = "claude-haiku-5-5",
) : FoodEstimator {

    override suspend fun estimate(text: String): FoodEstimate = withContext(Dispatchers.IO) {
        val key = apiKey() ?: throw NoApiKey()
        val client = AnthropicOkHttpClient.builder().apiKey(key).build()
        try {
            val params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(EstimatePrompt.SYSTEM)
                .addUserMessage(text)
                .outputConfig(
                    OutputConfig.builder()
                        // A quick lookup-style judgment; low effort keeps it fast and cheap.
                        .effort(OutputConfig.Effort.LOW)
                        .format(
                            JsonOutputFormat.builder()
                                .schema(JsonOutputFormat.Schema.builder().apply {
                                    EstimatePrompt.SCHEMA.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                                }.build())
                                .build()
                        )
                        .build()
                )
                .build()

            val response = client.messages().create(params)
            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                throw EstimateFailed("The model declined to estimate this entry")
            }
            val json = response.content().firstNotNullOfOrNull { it.text().orElse(null)?.text() }
                ?: throw EstimateFailed("The estimate came back empty")
            EstimatePrompt.parse(json)
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
