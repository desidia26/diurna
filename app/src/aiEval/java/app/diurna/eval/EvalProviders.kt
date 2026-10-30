package app.diurna.eval

import app.diurna.ai.CardioPrompt
import app.diurna.ai.CardioReading
import app.diurna.ai.ClaudeCardioReader
import app.diurna.ai.ClaudeFoodEstimator
import app.diurna.ai.EstimatePrompt
import app.diurna.ai.FoodEstimate
import app.diurna.ai.GrokCardioReader
import app.diurna.ai.GrokFoodEstimator
import app.diurna.ai.XaiChat
import app.diurna.ai.XaiTransport
import org.json.JSONObject
import java.io.IOException

enum class EvalProviderId(val id: String, val label: String) {
    GROK("grok", "Grok (xAI)"),
    CLAUDE("claude", "Claude (Anthropic)"),
    OLLAMA("ollama", "Ollama (smoke)"),
    ;

    companion object {
        fun parse(raw: String): List<EvalProviderId> = when (raw.trim().lowercase()) {
            "all" -> entries.toList()
            else -> listOf(entries.firstOrNull { it.id == raw.trim().lowercase() }
                ?: error("Unknown provider '$raw' (want grok|claude|ollama|all)"))
        }
    }
}

/** How accuracy bands are treated when grading a provider. */
enum class ScoringMode {
    /** Band/tolerance misses fail the case (Grok/Claude). */
    ACCURACY,
    /**
     * Only parse/structure health fails the case. Band checks are recorded as informational
     * so a weak local smoke model does not fail the build on nutrition accuracy.
     */
    PARSE_HEALTH,
}

sealed class ProviderSkip(message: String) : Exception(message)
class MissingKeySkip(provider: String, envVar: String) :
    ProviderSkip("$provider skipped: $envVar is not set")
class UnavailableSkip(provider: String, detail: String) :
    ProviderSkip("$provider skipped: $detail")
class RateLimitedSkip(provider: String) :
    ProviderSkip("$provider skipped: rate limited (HTTP 429)")

interface EvalFoodClient {
    val providerId: String
    val model: String
    suspend fun estimate(text: String, photo: ByteArray?): FoodEstimate
}

interface EvalCardioClient {
    val providerId: String
    val model: String
    suspend fun read(photo: ByteArray): CardioReading
}

data class EvalClients(
    val food: EvalFoodClient,
    val cardio: EvalCardioClient?,
    val scoringMode: ScoringMode = ScoringMode.ACCURACY,
)

object EvalProviders {
    suspend fun resolve(
        id: EvalProviderId,
        grokModel: String?,
        claudeModel: String?,
        ollamaModel: String?,
        ollamaBaseUrl: String?,
        env: (String) -> String? = { System.getenv(it) },
    ): EvalClients = when (id) {
        EvalProviderId.GROK -> {
            val key = env("XAI_API_KEY")?.takeIf { it.isNotBlank() }
                ?: throw MissingKeySkip("grok", "XAI_API_KEY")
            val model = grokModel?.takeIf { it.isNotBlank() } ?: GrokFoodEstimator.DEFAULT_MODEL
            val food = object : EvalFoodClient {
                override val providerId = id.id
                override val model = model
                private val est = GrokFoodEstimator({ key }, { model })
                override suspend fun estimate(text: String, photo: ByteArray?) = est.estimate(text, photo)
            }
            val cardio = object : EvalCardioClient {
                override val providerId = id.id
                override val model = model
                private val reader = GrokCardioReader({ key }, { model })
                override suspend fun read(photo: ByteArray) = reader.read(photo)
            }
            EvalClients(food, cardio, ScoringMode.ACCURACY)
        }
        EvalProviderId.CLAUDE -> {
            val key = env("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
                ?: throw MissingKeySkip("claude", "ANTHROPIC_API_KEY")
            val model = claudeModel?.takeIf { it.isNotBlank() } ?: "claude-haiku-5-5"
            val food = object : EvalFoodClient {
                override val providerId = id.id
                override val model = model
                private val est = ClaudeFoodEstimator({ key }, model)
                override suspend fun estimate(text: String, photo: ByteArray?) = est.estimate(text, photo)
            }
            val cardio = object : EvalCardioClient {
                override val providerId = id.id
                override val model = model
                private val reader = ClaudeCardioReader({ key }, model)
                override suspend fun read(photo: ByteArray) = reader.read(photo)
            }
            EvalClients(food, cardio, ScoringMode.ACCURACY)
        }
        EvalProviderId.OLLAMA -> {
            val base = (ollamaBaseUrl?.takeIf { it.isNotBlank() }
                ?: env("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
                ?: OllamaChat.DEFAULT_BASE_URL).trimEnd('/')
            val model = ollamaModel?.takeIf { it.isNotBlank() }
                ?: env("OLLAMA_MODEL")?.takeIf { it.isNotBlank() }
                ?: OllamaChat.DEFAULT_MODEL
            val key = env("OLLAMA_API_KEY")?.takeIf { it.isNotBlank() } ?: "ollama"
            val endpoint = "$base/chat/completions"
            val transport = OpenAiCompatTransport(endpoint)
            // Probe once so a missing daemon is a clean skip, not N case failures.
            OllamaChat.ensureReachable(transport, key)
            val food = object : EvalFoodClient {
                override val providerId = id.id
                override val model = model
                override suspend fun estimate(text: String, photo: ByteArray?): FoodEstimate {
                    val body = OllamaChat.request(model, EstimatePrompt.SYSTEM, text, photo)
                    return EstimatePrompt.parse(OllamaChat.call(transport, key, body))
                }
            }
            val cardio = object : EvalCardioClient {
                override val providerId = id.id
                override val model = model
                override suspend fun read(photo: ByteArray): CardioReading {
                    val body = OllamaChat.request(
                        model, CardioPrompt.SYSTEM, CardioPrompt.USER_TEXT, photo,
                        schemaName = "cardio_summary", schema = CardioPrompt.SCHEMA,
                    )
                    return CardioPrompt.parse(OllamaChat.call(transport, key, body))
                }
            }
            EvalClients(food, cardio, ScoringMode.PARSE_HEALTH)
        }
    }
}

/**
 * OpenAI-compatible chat against a local Ollama (or any compatible) server.
 *
 * This is a **smoke** path: prompt + parse health on a substitute CPU model, not an accuracy
 * eval of the app’s real Grok/Claude providers. Eval-side only — the app’s OpenAI-compatible
 * gateway (#21) is a separate production path; covering it here would need a cheap fixture
 * endpoint and is left as a follow-up.
 */
object OllamaChat {
    const val DEFAULT_BASE_URL = "http://localhost:11434/v1"
    /** ~1.5B instruct; emits JSON reliably on CPU (see docs/ai-evals.md). */
    const val DEFAULT_MODEL = "qwen2.5:1.5b-instruct"

    fun request(
        model: String,
        system: String,
        userText: String,
        photoJpeg: ByteArray? = null,
        schemaName: String = "food_estimate",
        schema: Map<String, Any> = EstimatePrompt.SCHEMA,
    ): String {
        // Reuse the app’s OpenAI-shaped builder, then rename max_completion_tokens → max_tokens
        // for broader OpenAI-compat hosts (Ollama’s /v1 reads max_tokens).
        val raw = XaiChat.request(model, system, userText, photoJpeg, schemaName, schema)
        val obj = JSONObject(raw)
        if (obj.has("max_completion_tokens") && !obj.has("max_tokens")) {
            obj.put("max_tokens", obj.getInt("max_completion_tokens"))
            obj.remove("max_completion_tokens")
        }
        return obj.toString()
    }

    suspend fun ensureReachable(transport: XaiTransport, key: String) {
        // Tiny no-op post is wrong; use a HEAD-less GET via a failed tiny completion is heavy.
        // Instead: TCP-level probe by posting an empty model list isn't available on XaiTransport.
        // Callers that fail on first case still short-circuit via UnavailableSkip.
        try {
            // Hit the server with an intentionally tiny invalid body so we get a fast 4xx,
            // proving the daemon is up, without loading a model.
            val probe = transport.post(key, """{"model":"__diurna_probe__","messages":[{"role":"user","content":"ping"}],"max_tokens":1,"stream":false}""")
            when (probe.status) {
                in 200..499 -> return // up (even 404 model-not-found means the daemon answered)
                else -> throw UnavailableSkip("ollama", "HTTP ${probe.status} probing $DEFAULT_BASE_URL")
            }
        } catch (e: ProviderSkip) {
            throw e
        } catch (e: IOException) {
            throw UnavailableSkip("ollama", "not reachable (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    suspend fun call(transport: XaiTransport, key: String, request: String): String {
        val reply = try {
            transport.post(key, request)
        } catch (e: IOException) {
            throw UnavailableSkip("ollama", "network error (${e.javaClass.simpleName})")
        }
        when (reply.status) {
            429 -> throw RateLimitedSkip("ollama")
            404 -> throw UnavailableSkip("ollama", "HTTP 404 (model not pulled, or endpoint wrong)")
        }
        if (reply.status !in 200..299) {
            throw UnavailableSkip("ollama", "HTTP ${reply.status}: ${reply.body.take(200)}")
        }
        return try {
            XaiChat.content(reply.body)
        } catch (e: Exception) {
            throw UnavailableSkip("ollama", "could not read chat content: ${e.message}")
        }
    }
}
