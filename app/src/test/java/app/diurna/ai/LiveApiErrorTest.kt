package app.diurna.ai

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Calls the real API with a deliberately invalid key, to check how a live error surfaces through
 * the SDK and that it lands on the right message. Opt-in, needs internet, costs nothing (rejected
 * requests aren't billed):  LIVE_API=1 ./gradlew testDebugUnitTest --tests '*LiveApiErrorTest*'
 */
class LiveApiErrorTest {
    private val fakeKey = "sk-ant-api03-daybook-deliberately-invalid-key"

    @Before fun optIn() = assumeTrue("set LIVE_API=1 to run", System.getenv("LIVE_API") == "1")

    @Test fun rawSdkErrorShape() {
        val client = AnthropicOkHttpClient.builder().apiKey(fakeKey).build()
        try {
            client.messages().create(
                MessageCreateParams.builder().model("claude-opus-5-5").maxTokens(16L).addUserMessage("hi").build()
            )
            error("expected the request to be rejected")
        } catch (e: AnthropicServiceException) {
            println("LIVE status=${e.statusCode()} class=${e.javaClass.simpleName}")
            println("LIVE message=${e.message}")
            assertEquals(401, e.statusCode())
        } finally {
            client.close()
        }
    }

    @Test fun invalidKeyShowsTheKeyMessage() = runTest {
        try {
            ClaudeFoodEstimator({ fakeKey }).estimate("two eggs")
            error("expected EstimateFailed")
        } catch (e: EstimateFailed) {
            println("LIVE shown=${e.message}")
            assertEquals(EstimateErrors.BAD_KEY, e.message)
        }
    }
}
