package app.diurna.eval

import app.diurna.ai.CardioPrompt
import app.diurna.ai.CardioReading
import app.diurna.ai.ClaudeCardioReader
import app.diurna.ai.ClaudeFoodEstimator
import app.diurna.ai.BarcodeIngredientsContext
import app.diurna.ai.BarcodeIngredientsPrompt
import app.diurna.ai.ClaudeLabelReader
import app.diurna.ai.EstimatePrompt
import app.diurna.domain.FoodIngredient
import app.diurna.ai.FoodEstimate
import app.diurna.ai.GrokCardioReader
import app.diurna.ai.GrokFoodEstimator
import app.diurna.ai.GrokLabelReader
import app.diurna.ai.NutritionLabelReading
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
    suspend fun estimate(
        text: String,
        photo: ByteArray?,
        detailed: Boolean = false,
        ingredients: Boolean = false,
    ): FoodEstimate
    suspend fun guessBarcodeIngredients(ctx: app.diurna.ai.BarcodeIngredientsContext): List<app.diurna.domain.FoodIngredient>
}

interface EvalCardioClient {
    val providerId: String
    val model: String
    suspend fun read(photo: ByteArray): CardioReading
}

interface EvalLabelClient {
    val providerId: String
    val model: String
    suspend fun read(photo: ByteArray): NutritionLabelReading
}

interface EvalPackageClient {
    val providerId: String
    val model: String
    suspend fun read(photo: ByteArray): app.diurna.ai.PackagePhotoReading
    /** One package's close-up crop, as [app.diurna.ai.PackageReader.readCloseUp]. */
    suspend fun readCloseUp(photo: ByteArray): app.diurna.ai.PackagePhotoReading = read(photo)
    /** The app's reader behind this client, for the two-pass pipeline; null when there isn't one. */
    val reader: app.diurna.ai.PackageReader? get() = null
    suspend fun refine(photo: ByteArray?, previous: app.diurna.ai.PackagePhotoReading, answer: String): app.diurna.ai.PackagePhotoReading
}

interface EvalDensityClient {
    val providerId: String
    val model: String
    suspend fun estimate(name: String, kind: app.diurna.domain.DensityUnitKind): app.diurna.domain.DensityLookup
}

interface EvalIngredientNutritionClient {
    val providerId: String
    val model: String
    suspend fun estimate(name: String): app.diurna.data.fdc.FdcNutritionProfile
}

data class EvalClients(
    val food: EvalFoodClient,
    val cardio: EvalCardioClient?,
    val label: EvalLabelClient?,
    val density: EvalDensityClient? = null,
    val ingredientNutrition: EvalIngredientNutritionClient? = null,
    val scoringMode: ScoringMode = ScoringMode.ACCURACY,
    val pkg: EvalPackageClient? = null,
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
                override suspend fun estimate(
                    text: String,
                    photo: ByteArray?,
                    detailed: Boolean,
                    ingredients: Boolean,
                ) = est.estimate(text, photo, detailed, ingredients)
                override suspend fun guessBarcodeIngredients(ctx: BarcodeIngredientsContext) =
                    est.guessBarcodeIngredients(ctx)
            }
            val cardio = object : EvalCardioClient {
                override val providerId = id.id
                override val model = model
                private val reader = GrokCardioReader({ key }, { model })
                override suspend fun read(photo: ByteArray) = reader.read(photo)
            }
            val label = object : EvalLabelClient {
                override val providerId = id.id
                override val model = model
                private val reader = GrokLabelReader({ key }, model)
                override suspend fun read(photo: ByteArray) = reader.read(photo)
            }
            val density = object : EvalDensityClient {
                override val providerId = id.id
                override val model = model
                private val est = app.diurna.ai.GrokDensityEstimator({ key }, { model }, { app.diurna.ai.HttpXaiTransport() })
                override suspend fun estimate(name: String, kind: app.diurna.domain.DensityUnitKind) =
                    est.estimate(name, kind)
            }
            val ingredientNutrition = object : EvalIngredientNutritionClient {
                override val providerId = id.id
                override val model = model
                private val est = app.diurna.ai.GrokNutritionEstimator({ key }, { model }, { app.diurna.ai.HttpXaiTransport() })
                override suspend fun estimate(name: String) = est.estimate(name)
            }
            val pkg = object : EvalPackageClient {
                override val providerId = id.id
                override val model = model
                override val reader = app.diurna.ai.GrokPackageReader({ key }, model)
                override suspend fun read(photo: ByteArray) = reader.read(photo)
                override suspend fun readCloseUp(photo: ByteArray) = reader.readCloseUp(photo)
                override suspend fun refine(photo: ByteArray?, previous: app.diurna.ai.PackagePhotoReading, answer: String) = reader.refine(photo, previous, answer)
            }
            EvalClients(food, cardio, label, density, ingredientNutrition, ScoringMode.ACCURACY, pkg = pkg)
        }
        EvalProviderId.CLAUDE -> {
            val key = env("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }
                ?: throw MissingKeySkip("claude", "ANTHROPIC_API_KEY")
            val model = claudeModel?.takeIf { it.isNotBlank() } ?: "claude-haiku-5-5"
            val food = object : EvalFoodClient {
                override val providerId = id.id
                override val model = model
                private val est = ClaudeFoodEstimator({ key }, model)
                override suspend fun estimate(
                    text: String,
                    photo: ByteArray?,
                    detailed: Boolean,
                    ingredients: Boolean,
                ) = est.estimate(text, photo, detailed, ingredients)
                override suspend fun guessBarcodeIngredients(ctx: BarcodeIngredientsContext) =
                    est.guessBarcodeIngredients(ctx)
            }
            val cardio = object : EvalCardioClient {
                override val providerId = id.id
                override val model = model
                private val reader = ClaudeCardioReader({ key }, model)
                override suspend fun read(photo: ByteArray) = reader.read(photo)
            }
            val label = object : EvalLabelClient {
                override val providerId = id.id
                override val model = model
                private val reader = ClaudeLabelReader({ key }, model)
                override suspend fun read(photo: ByteArray) = reader.read(photo)
            }
            val density = object : EvalDensityClient {
                override val providerId = id.id
                override val model = model
                private val est = app.diurna.ai.ClaudeDensityEstimator({ key }, { model })
                override suspend fun estimate(name: String, kind: app.diurna.domain.DensityUnitKind) =
                    est.estimate(name, kind)
            }
            val ingredientNutrition = object : EvalIngredientNutritionClient {
                override val providerId = id.id
                override val model = model
                private val est = app.diurna.ai.ClaudeNutritionEstimator({ key }, { model })
                override suspend fun estimate(name: String) = est.estimate(name)
            }
            val pkg = object : EvalPackageClient {
                override val providerId = id.id
                override val model = model
                override val reader = app.diurna.ai.ClaudePackageReader({ key }, model)
                override suspend fun read(photo: ByteArray) = reader.read(photo)
                override suspend fun readCloseUp(photo: ByteArray) = reader.readCloseUp(photo)
                override suspend fun refine(photo: ByteArray?, previous: app.diurna.ai.PackagePhotoReading, answer: String) = reader.refine(photo, previous, answer)
            }
            EvalClients(food, cardio, label, density, ingredientNutrition, ScoringMode.ACCURACY, pkg = pkg)
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
                override suspend fun estimate(
                    text: String,
                    photo: ByteArray?,
                    detailed: Boolean,
                    ingredients: Boolean,
                ): FoodEstimate {
                    val body = OllamaChat.request(
                        model,
                        EstimatePrompt.system(detailed, ingredients),
                        text,
                        photo,
                        schemaName = "food_estimate",
                        schema = EstimatePrompt.schema(detailed, ingredients),
                    )
                    return EstimatePrompt.parse(OllamaChat.call(transport, key, body))
                }
                override suspend fun guessBarcodeIngredients(ctx: BarcodeIngredientsContext): List<FoodIngredient> {
                    val body = OllamaChat.request(
                        model,
                        BarcodeIngredientsPrompt.SYSTEM,
                        BarcodeIngredientsPrompt.userMessage(ctx),
                        photoJpeg = null,
                        schemaName = "barcode_ingredients",
                        schema = BarcodeIngredientsPrompt.SCHEMA,
                    )
                    return BarcodeIngredientsPrompt.parse(OllamaChat.call(transport, key, body))
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
            // Label is vision-only; Ollama smoke skips vision by default (no label client needed).
            val density = object : EvalDensityClient {
                override val providerId = id.id
                override val model = model
                override suspend fun estimate(
                    name: String,
                    kind: app.diurna.domain.DensityUnitKind,
                ): app.diurna.domain.DensityLookup {
                    val body = OllamaChat.request(
                        model,
                        app.diurna.ai.DensityPrompt.SYSTEM,
                        app.diurna.ai.DensityPrompt.userText(name, kind),
                        photoJpeg = null,
                        schemaName = "ingredient_density",
                        schema = app.diurna.ai.DensityPrompt.SCHEMA,
                    )
                    return app.diurna.ai.DensityPrompt.parse(
                        OllamaChat.call(transport, key, body),
                        kind,
                    )
                }
            }
            val ingredientNutrition = object : EvalIngredientNutritionClient {
                override val providerId = id.id
                override val model = model
                override suspend fun estimate(name: String): app.diurna.data.fdc.FdcNutritionProfile {
                    val body = OllamaChat.request(
                        model,
                        app.diurna.ai.IngredientNutritionPrompt.SYSTEM,
                        app.diurna.ai.IngredientNutritionPrompt.userText(name),
                        photoJpeg = null,
                        schemaName = "ingredient_nutrition",
                        schema = app.diurna.ai.IngredientNutritionPrompt.SCHEMA,
                    )
                    return app.diurna.ai.IngredientNutritionPrompt.parse(
                        OllamaChat.call(transport, key, body),
                    )
                }
            }
            EvalClients(
                food, cardio, label = null, density = density,
                ingredientNutrition = ingredientNutrition,
                scoringMode = ScoringMode.PARSE_HEALTH,
            )
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
