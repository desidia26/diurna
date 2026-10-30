package app.diurna.ai

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenAiCompatTest {
    private val estimateJson = """{"kcal_low":650,"kcal_high":950,"protein_g":45,"carbs_g":80,"fat_g":28,"note":"1 cup rice"}"""

    private fun reply(content: String?) = JSONObject()
        .put("choices", org.json.JSONArray().put(JSONObject()
            .put("finish_reason", "stop")
            .put("message", JSONObject().put("role", "assistant").put("content", content))))
        .toString()

    private class FakeTransport(var answer: () -> XaiReply) : XaiTransport {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun post(apiKey: String, json: String): XaiReply {
            sent += apiKey to json
            return answer()
        }
    }

    @Test fun requestBuildingMatchesOpenAiChatCompletions() {
        val r = JSONObject(XaiChat.request("llama3.2", "SYS", "salad"))
        assertEquals("llama3.2", r.getString("model"))
        assertEquals("json_schema", r.getJSONObject("response_format").getString("type"))
        assertFalse(r.getBoolean("stream"))
        assertEquals(XaiChat.MAX_COMPLETION_TOKENS, r.getInt("max_completion_tokens"))
    }

    @Test fun emptyKeyIsAllowedAndStillPosts() = runTest {
        val t = FakeTransport { XaiReply(200, reply(estimateJson)) }
        val e = OpenAiCompatFoodEstimator(
            apiKey = { null },
            modelProvider = { "local-model" },
            transportProvider = { t },
            configured = { true },
        ).estimate("rice")
        assertEquals(650, e.kcalLow)
        assertEquals("", t.sent.single().first)
        assertEquals("local-model", JSONObject(t.sent.single().second).getString("model"))
    }

    @Test fun missingGatewayConfigAsksToSetUrlAndModel() = runTest {
        val t = FakeTransport { error("no") }
        val err = runCatching {
            OpenAiCompatFoodEstimator({ "k" }, { "m" }, { t }, configured = { false }).estimate("x")
        }.exceptionOrNull()
        assertTrue(err is NoApiKey)
        assertEquals(OpenAiCompatFoodEstimator.NO_CONFIG, err!!.message)
        assertTrue(t.sent.isEmpty())
    }

    @Test fun visionOffFallsBackToTextWithNote() = runTest {
        val t = FakeTransport { XaiReply(200, reply(estimateJson)) }
        val e = OpenAiCompatFoodEstimator(
            { "k" }, { "m" }, { t },
            supportsImages = { false },
        ).estimate("burrito", byteArrayOf(1, 2, 3))
        val user = JSONObject(t.sent.single().second).getJSONArray("messages").getJSONObject(1)
        assertEquals("burrito", user.getString("content")) // plain text, no image part
        assertTrue(e.note.contains("Photo not sent"))
    }

    @Test fun visionUnsupportedApiErrorIsClear() = runTest {
        val body = """{"error":{"message":"This model does not support image inputs"}}"""
        val t = FakeTransport { XaiReply(400, body) }
        val err = runCatching {
            OpenAiCompatFoodEstimator({ "k" }, { "m" }, { t }).estimate("x", byteArrayOf(9))
        }.exceptionOrNull()
        assertTrue(err is EstimateFailed)
        assertEquals(VisionSupport.API_REJECTED_IMAGE, err!!.message)
    }

    @Test fun cardioRefusesWhenVisionOff() = runTest {
        val t = FakeTransport { error("no") }
        val err = runCatching {
            OpenAiCompatCardioReader({ "k" }, { "m" }, { t }, supportsImages = { false }).read(byteArrayOf(1))
        }.exceptionOrNull()
        assertTrue(err is EstimateFailed)
        assertEquals(VisionSupport.PHOTO_FEATURES_DISABLED, err!!.message)
    }

    @Test fun defaultXaiEndpointConstantUnchanged() {
        assertEquals("https://api.x.ai/v1/chat/completions", XaiChat.ENDPOINT)
    }

    @Test fun classifyDoesNotLeakBodies() {
        val m = (OpenAiCompatErrors.classify(418, "secret-token-xyz", "m") as EstimateErrors.Fail).message
        assertFalse(m.contains("secret"))
    }

    @Test fun networkErrorsRetry() = runTest {
        val t = FakeTransport { throw IOException("offline") }
        val err = runCatching {
            OpenAiCompatFoodEstimator({ "k" }, { "m" }, { t }).estimate("x")
        }.exceptionOrNull()
        assertTrue(err is EstimateRetryable)
    }
}

class VisionSupportTest {
    @Test fun detectsCommonUnsupportedImageBodies() {
        assertTrue(VisionSupport.isUnsupportedImageError(400, "model does not support image inputs"))
        assertTrue(VisionSupport.isUnsupportedImageError(400, "Unsupported image modality"))
        assertTrue(VisionSupport.isUnsupportedImageError(422, "invalid_image"))
        assertFalse(VisionSupport.isUnsupportedImageError(400, "messages: text content blocks must be non-empty"))
        assertFalse(VisionSupport.isUnsupportedImageError(401, "invalid api key with image in the log"))
        assertFalse(VisionSupport.isUnsupportedImageError(500, "image processing failed"))
    }

    @Test fun noteWithoutPhotoAppendsOnce() {
        assertEquals("1 cup; Photo not sent (model has no vision)", VisionSupport.noteWithoutPhoto("1 cup"))
        assertEquals("Photo not sent (model has no vision)", VisionSupport.noteWithoutPhoto("  "))
    }
}
