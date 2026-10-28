package app.diurna.eval

import app.diurna.ai.XaiReply
import app.diurna.ai.XaiTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubModelsChatTest {
    @Test fun stubOkBodyIsUnavailableSkip() = runTest {
        val t = XaiTransport { _, _ -> XaiReply(200, "OK") }
        val e = assertThrows(UnavailableSkip::class.java) {
            kotlinx.coroutines.runBlocking { GitHubModelsChat.call(t, "tok", "xai/grok-3-mini", "{}") }
        }
        assertTrue(e.message!!.contains("retired") || e.message!!.contains("non-JSON"))
    }

    @Test fun http429IsRateLimitedSkip() = runTest {
        val t = XaiTransport { _, _ -> XaiReply(429, """{"error":"rate"}""") }
        assertThrows(RateLimitedSkip::class.java) {
            kotlinx.coroutines.runBlocking { GitHubModelsChat.call(t, "tok", "xai/grok-3-mini", "{}") }
        }
    }

    @Test fun parsesOpenAiShapedReply() = runTest {
        val body = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"{\"kcal_low\":1,\"kcal_high\":2,\"protein_g\":0,\"carbs_g\":0,\"fat_g\":0,\"note\":\"x\"}"}}]}"""
        val t = XaiTransport { _, _ -> XaiReply(200, body) }
        val content = GitHubModelsChat.call(t, "tok", "xai/grok-3-mini", "{}")
        assertTrue(content.contains("kcal_low"))
    }

    @Test fun providerIdParse() {
        assertEquals(listOf(EvalProviderId.GITHUB_MODELS), EvalProviderId.parse("github-models"))
        assertEquals(3, EvalProviderId.parse("all").size)
    }
}
