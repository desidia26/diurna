package app.daybook.ai

import app.daybook.ai.EstimateErrors.Fail
import app.daybook.ai.EstimateErrors.Retry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message texts below are the documented examples from platform.claude.com (checked 2026-10-13). */
class EstimateErrorsTest {
    private fun c(status: Int, message: String? = null) = EstimateErrors.classify(status, message, "claude-test")

    @Test fun tierSpendCapIsA429ButMustNotRetry() {
        val body = """{"type":"error","error":{"type":"rate_limit_error","message":"You have reached your API usage limits: your organization has crossed its monthly API usage threshold, set based on your organization's API tier. You will regain access on 2026-09-01 at 00:00 UTC.","details":{"error_code":"enforced_spend_limit_reached"}},"request_id":"req_018EeWyXxfu5pfWkrYcMdjWG"}"""
        assertEquals(Fail(EstimateErrors.spendLimit("2026-09-01")), c(429, "429: $body"))
    }

    @Test fun errorCodeAloneIsEnough() {
        assertEquals(Fail(EstimateErrors.spendLimit(null)), c(429, """{"details":{"error_code":"enforced_spend_limit_reached"}}"""))
    }

    @Test fun spendLimitYouSetIsA400() {
        val msg = "You have reached your specified API usage limits. You will regain access on 2026-11-01 at 00:00 UTC."
        assertEquals(Fail(EstimateErrors.spendLimit("2026-11-01")), c(400, msg))
        val ws = "You have reached your specified workspace API usage limits. You will regain access on 2026-11-01 at 00:00 UTC."
        assertEquals(Fail(EstimateErrors.spendLimit("2026-11-01")), c(400, ws))
    }

    @Test fun spendLimitMessageReadsWell() {
        assertEquals("Anthropic spend limit reached until 2026-11-01. Raise it in the Claude Console, then tap Retry.", EstimateErrors.spendLimit("2026-11-01"))
        assertEquals("Anthropic spend limit reached. Raise it in the Claude Console, then tap Retry.", EstimateErrors.spendLimit(null))
    }

    @Test fun ordinaryRateLimitStillRetries() {
        assertEquals(Retry, c(429, "Number of request tokens has exceeded your per-minute rate limit"))
    }

    @Test fun creditAndBillingAreDifferent() {
        assertEquals(Fail(EstimateErrors.NO_CREDIT), c(400, "Your credit balance is too low to access the Anthropic API."))
        assertEquals(Fail(EstimateErrors.BILLING), c(402, "billing_error"))
    }

    @Test fun keyAndPermissionProblems() {
        assertEquals(Fail(EstimateErrors.BAD_KEY), c(401, "invalid x-api-key"))
        assertTrue(c(403) is Fail)
        assertEquals(Fail("claude-test isn't available to this API key."), c(404))
    }

    @Test fun temporaryProblemsRetry() {
        listOf(408, 409, 429, 500, 504, 529).forEach { assertEquals("HTTP $it", Retry, c(it, "overloaded")) }
    }

    @Test fun badRequestsAskForRewording() {
        assertEquals(Fail("Claude couldn't process this entry. Try rewording it."), c(400, "messages: text content blocks must be non-empty"))
        assertTrue(c(413) is Fail)
    }

    @Test fun anythingElseFailsVisiblyInsteadOfLooping() {
        assertEquals(Fail("Unexpected error from Claude (HTTP 418). Tap Retry."), c(418))
    }

    @Test fun messagesNeverLeakRawApiText() {
        val raw = """{"type":"error","error":{"type":"invalid_request_error","message":"secret detail"}}"""
        val out = c(400, raw) as Fail
        assertTrue("secret detail" !in out.message)
    }
}
