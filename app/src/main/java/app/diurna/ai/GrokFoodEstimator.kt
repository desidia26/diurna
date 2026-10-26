package app.diurna.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Estimates one entry with a single xAI chat completions call (OpenAI-compatible,
 * docs.x.ai, checked 2026-10-25). A strict json_schema response_format guarantees a reply that
 * matches [EstimatePrompt.SCHEMA], the same schema Claude uses, so both parse the same way.
 * Errors map to [EstimateRetryable] or [EstimateFailed] like [ClaudeFoodEstimator]'s.
 */
class GrokFoodEstimator(
    private val apiKey: () -> String?,
    override val model: String = DEFAULT_MODEL,
    private val transport: XaiTransport = HttpXaiTransport(),
) : FoodEstimator {

    override val gaveUpMessage: String get() = GrokErrors.GAVE_UP

    override suspend fun estimate(text: String): FoodEstimate {
        val key = apiKey() ?: throw NoApiKey(NO_KEY)
        val reply = try {
            transport.post(key, XaiChat.request(model, EstimatePrompt.SYSTEM, text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw EstimateRetryable(e) // offline, timeout, dropped connection
        }
        if (reply.status != 200) {
            when (val o = GrokErrors.classify(reply.status, reply.body, model)) {
                EstimateErrors.Retry -> throw EstimateRetryable(IOException("HTTP ${reply.status}"))
                is EstimateErrors.Fail -> throw EstimateFailed(o.message)
            }
        }
        return EstimatePrompt.parse(XaiChat.content(reply.body))
    }

    companion object {
        /**
         * Grok 4.20 without reasoning: takes text and images, supports structured outputs, and is
         * xAI's cheapest fast model, the closest match to Haiku at low effort for a lookup-style
         * estimate. The alias follows xAI's latest stable 4.20 release.
         */
        const val DEFAULT_MODEL = "grok-4.20-non-reasoning"
        const val NO_KEY = "Add an xAI (Grok) API key in Setup to estimate food"
    }
}

/** Builds requests and reads replies for xAI's chat completions. Plain functions, unit tested. */
object XaiChat {
    const val ENDPOINT = "https://api.x.ai/v1/chat/completions"

    /** The reply is a few dozen tokens; the cap only stops a runaway from costing much. */
    const val MAX_COMPLETION_TOKENS = 1024

    fun request(model: String, system: String, userText: String): String = JSONObject()
        .put("model", model)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", userText)))
        .put("response_format", JSONObject()
            .put("type", "json_schema")
            .put("json_schema", JSONObject()
                .put("name", "food_estimate")
                .put("strict", true)
                .put("schema", toJson(EstimatePrompt.SCHEMA))))
        .put("max_completion_tokens", MAX_COMPLETION_TOKENS)
        .put("stream", false)
        .toString()

    /** The structured JSON text from a 200 reply. A refusal or an empty or cut-off reply fails. */
    fun content(body: String): String {
        val message = try {
            JSONObject(body).getJSONArray("choices").getJSONObject(0).let { choice ->
                if (choice.optString("finish_reason") == "length") throw EstimateFailed("The estimate was cut off. Tap Retry.")
                choice.getJSONObject("message")
            }
        } catch (e: JSONException) {
            throw EstimateFailed("Couldn't read the estimate")
        }
        if (!message.isNull("refusal") && message.optString("refusal").isNotBlank()) {
            throw EstimateFailed("The model declined to estimate this entry")
        }
        return message.optString("content").takeIf { !message.isNull("content") && it.isNotBlank() }
            ?: throw EstimateFailed("The estimate came back empty")
    }

    private fun toJson(value: Any?): Any? = when (value) {
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } }
        is List<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        else -> value
    }
}

/** One HTTP answer; non-2xx bodies are kept for classifying, never shown. */
data class XaiReply(val status: Int, val body: String)

/** How a request reaches xAI; a fake in tests. Throws [IOException] when it can't get through. */
fun interface XaiTransport {
    suspend fun post(apiKey: String, json: String): XaiReply
}

/**
 * HTTPS only, no redirects (a redirect could carry the key elsewhere), a total deadline and a
 * size cap on the answer, like the Open Food Facts lookup. The key goes in the Authorization
 * header only.
 */
class HttpXaiTransport(private val endpoint: String = XaiChat.ENDPOINT) : XaiTransport {
    override suspend fun post(apiKey: String, json: String): XaiReply = withContext(Dispatchers.IO) {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        coroutineScope {
            // Per-read timeouts alone don't bound a server that drips bytes slowly.
            val deadline = launch { delay(DEADLINE_MS); conn.disconnect() }
            try {
                conn.instanceFollowRedirects = false
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                XaiReply(status, stream?.use { readCapped(it) }.orEmpty())
            } finally {
                deadline.cancel()
                conn.disconnect()
            }
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw IOException("response too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val DEADLINE_MS = 90_000L
        /** An estimate reply is well under 4 KB. */
        const val MAX_BYTES = 256 * 1024
    }
}
