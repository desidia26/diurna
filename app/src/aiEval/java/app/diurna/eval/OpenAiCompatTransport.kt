package app.diurna.eval

import app.diurna.ai.XaiReply
import app.diurna.ai.XaiTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Eval-only OpenAI-compatible chat client (HTTP or HTTPS). Mirrors
 * [app.diurna.ai.HttpXaiTransport] with a configurable endpoint and a longer deadline so
 * CPU-local models (Ollama) can finish. Not used by the app at runtime.
 */
class OpenAiCompatTransport(
    private val endpoint: String,
    private val deadlineMs: Long = DEADLINE_MS,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 300_000,
) : XaiTransport {
    override suspend fun post(apiKey: String, json: String): XaiReply = withContext(Dispatchers.IO) {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        coroutineScope {
            val deadline = launch { delay(deadlineMs); conn.disconnect() }
            try {
                conn.instanceFollowRedirects = false
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "diurna-ai-evals")
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
        const val DEADLINE_MS = 360_000L
        const val MAX_BYTES = 256 * 1024
    }
}
