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
import app.diurna.ai.XaiReply
import app.diurna.ai.XaiTransport
import kotlinx.coroutines.delay
import java.io.IOException

enum class EvalProviderId(val id: String, val label: String) {
    GROK("grok", "Grok (xAI)"),
    CLAUDE("claude", "Claude (Anthropic)"),
    GITHUB_MODELS("github-models", "GitHub Models (smoke)"),
    ;

    companion object {
        fun parse(raw: String): List<EvalProviderId> = when (raw.trim().lowercase()) {
            "all" -> entries.toList()
            "github", "github-models", "github_models" -> listOf(GITHUB_MODELS)
            else -> listOf(entries.firstOrNull { it.id == raw.trim().lowercase() }
                ?: error("Unknown provider '$raw' (want grok|claude|github-models|all)"))
        }
    }
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

data class EvalClients(val food: EvalFoodClient, val cardio: EvalCardioClient?)

object EvalProviders {
    fun resolve(
        id: EvalProviderId,
        grokModel: String?,
        claudeModel: String?,
        githubModel: String?,
        env: (String) -> String? = { System.getenv(it) },
    ): EvalClients = when (id) {
        EvalProviderId.GROK -> {
            val key = env("XAI_API_KEY")?.takeIf { it.isNotBlank() }
                ?: throw MissingKeySkip("grok", "XAI_API_KEY")
            val model = grokModel?.takeIf { it.isNotBlank() } ?: GrokFoodEstimator.DEFAULT_MODEL
            val food = object : EvalFoodClient {
                override val providerId = id.id
                override val model = model
                private val est = GrokFoodEstimator({ key }, model)
                override suspend fun estimate(text: String, photo: ByteArray?) = est.estimate(text, photo)
            }
            val cardio = object : EvalCardioClient {
                override val providerId = id.id
                override val model = model
                private val reader = GrokCardioReader({ key }, model)
                override suspend fun read(photo: ByteArray) = reader.read(photo)
            }
            EvalClients(food, cardio)
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
            EvalClients(food, cardio)
        }
        EvalProviderId.GITHUB_MODELS -> {
            // Prefer GITHUB_TOKEN (Actions); GH_TOKEN is what `gh` often exports locally.
            val key = env("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
                ?: env("GH_TOKEN")?.takeIf { it.isNotBlank() }
                ?: throw MissingKeySkip("github-models", "GITHUB_TOKEN")
            val model = githubModel?.takeIf { it.isNotBlank() } ?: GitHubModelsChat.DEFAULT_MODEL
            val transport = RateLimitedTransport(
                inner = GitHubModelsTransport(),
                minIntervalMs = GitHubModelsChat.MIN_INTERVAL_MS,
            )
            val food = object : EvalFoodClient {
                override val providerId = id.id
                override val model = model
                override suspend fun estimate(text: String, photo: ByteArray?): FoodEstimate {
                    val body = XaiChat.request(model, EstimatePrompt.SYSTEM, text, photo)
                    return EstimatePrompt.parse(GitHubModelsChat.call(transport, key, model, body))
                }
            }
            // Vision + structured output on the free tier is flaky; cardio stays optional.
            val cardio = object : EvalCardioClient {
                override val providerId = id.id
                override val model = model
                override suspend fun read(photo: ByteArray): CardioReading {
                    val body = XaiChat.request(
                        model, CardioPrompt.SYSTEM, CardioPrompt.USER_TEXT, photo,
                        schemaName = "cardio_summary", schema = CardioPrompt.SCHEMA,
                    )
                    return CardioPrompt.parse(GitHubModelsChat.call(transport, key, model, body))
                }
            }
            EvalClients(food, cardio)
        }
    }
}

/**
 * OpenAI-compatible chat completions on GitHub Models.
 *
 * Note (2026-10): GitHub Models was retired on 2026-07-30. The endpoint currently returns a
 * plain-text "OK" stub. This client detects that and surfaces [UnavailableSkip] so CI skips
 * instead of failing. Kept so a revival (or a compatible successor behind the same URL) works
 * without another harness rewrite. This is a **smoke** path on a substitute model, not an
 * accuracy eval of the app's real Grok/Claude providers.
 */
object GitHubModelsChat {
    const val ENDPOINT = "https://models.github.ai/inference/chat/completions"
    /** Catalog-style id; override with -PgithubModel=… */
    const val DEFAULT_MODEL = "xai/grok-3-mini"
    /** Free tier for Grok 3 Mini was ~2 req/min; stay under that. */
    const val MIN_INTERVAL_MS = 35_000L

    suspend fun call(transport: XaiTransport, key: String, model: String, request: String): String {
        val reply = try {
            transport.post(key, request)
        } catch (e: IOException) {
            throw UnavailableSkip("github-models", "network error (${e.javaClass.simpleName})")
        }
        when (reply.status) {
            429 -> throw RateLimitedSkip("github-models")
            401, 403 -> throw UnavailableSkip(
                "github-models",
                "HTTP ${reply.status} (token missing models:read, or Models unavailable)",
            )
            404 -> throw UnavailableSkip("github-models", "HTTP 404 (endpoint or model gone)")
        }
        if (reply.status !in 200..299) {
            throw UnavailableSkip("github-models", "HTTP ${reply.status}")
        }
        val body = reply.body.trim()
        if (body.equals("OK", ignoreCase = true) || body.isEmpty() || (!body.startsWith("{") && !body.startsWith("["))) {
            throw UnavailableSkip(
                "github-models",
                "API returned a non-JSON body (GitHub Models retired 2026-07-30; endpoint is a stub)",
            )
        }
        return try {
            XaiChat.content(body)
        } catch (e: Exception) {
            // Some OpenAI-compatible hosts put JSON in choices[0].message.content already parsed path.
            throw UnavailableSkip("github-models", "could not read chat content: ${e.message}")
        }
    }
}

/** Serialises posts so we stay under a per-minute free-tier cap. */
class RateLimitedTransport(
    private val inner: XaiTransport,
    private val minIntervalMs: Long,
) : XaiTransport {
    private var lastAt = 0L
    override suspend fun post(apiKey: String, json: String): XaiReply {
        val now = System.currentTimeMillis()
        val wait = lastAt + minIntervalMs - now
        if (wait > 0) delay(wait)
        return try {
            inner.post(apiKey, json)
        } finally {
            lastAt = System.currentTimeMillis()
        }
    }
}
