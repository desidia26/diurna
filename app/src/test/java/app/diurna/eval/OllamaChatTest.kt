package app.diurna.eval

import app.diurna.ai.XaiReply
import app.diurna.ai.XaiTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaChatTest {
    @Test fun remapsMaxCompletionTokens() {
        val req = OllamaChat.request("m", "sys", "one apple")
        assertTrue(req.contains("\"max_tokens\""))
        assertFalse(req.contains("max_completion_tokens"))
        assertTrue(req.contains("json_schema"))
    }

    @Test fun http429IsRateLimitedSkip() {
        val t = XaiTransport { _, _ -> XaiReply(429, """{"error":"rate"}""") }
        assertThrows(RateLimitedSkip::class.java) {
            runBlocking { OllamaChat.call(t, "ollama", "{}") }
        }
    }

    @Test fun parsesOpenAiShapedReply() {
        val body = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\"kcal_low\":1,\"kcal_high\":2,\"protein_g\":0,\"carbs_g\":0,\"fat_g\":0,\"note\":\"x\"}"}}]}"""
        val t = XaiTransport { _, _ -> XaiReply(200, body) }
        val content = runBlocking { OllamaChat.call(t, "ollama", "{}") }
        assertTrue(content.contains("kcal_low"))
    }

    @Test fun unreachableProbeIsUnavailableSkip() {
        val t = XaiTransport { _, _ -> throw java.io.IOException("Connection refused") }
        val e = assertThrows(UnavailableSkip::class.java) {
            runBlocking { OllamaChat.ensureReachable(t, "ollama") }
        }
        assertTrue(e.message!!.contains("not reachable"))
    }

    @Test fun providerIdParse() {
        assertEquals(listOf(EvalProviderId.OLLAMA), EvalProviderId.parse("ollama"))
        assertEquals(3, EvalProviderId.parse("all").size)
        assertFalse(EvalProviderId.parse("all").any { it.id == "github-models" })
    }
}
