package app.diurna.ai

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class GrokFoodEstimatorTest {
    private val estimateJson = """{"kcal_low":650,"kcal_high":950,"protein_g":45,"carbs_g":80,"fat_g":28,"note":"1 cup rice"}"""

    private fun reply(content: String?, finish: String = "stop", refusal: String? = null) = JSONObject()
        .put("id", "x")
        .put("choices", org.json.JSONArray().put(JSONObject()
            .put("index", 0)
            .put("finish_reason", finish)
            .put("message", JSONObject()
                .put("role", "assistant")
                .put("content", content ?: JSONObject.NULL)
                .put("refusal", refusal ?: JSONObject.NULL))))
        .toString()

    private class FakeTransport(var answer: () -> XaiReply) : XaiTransport {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun post(apiKey: String, json: String): XaiReply { sent += apiKey to json; return answer() }
    }

    @Test fun requestCarriesModelPromptsAndStrictSchema() {
        val r = JSONObject(XaiChat.request("grok-test", "SYS", "burrito bowl"))
        assertEquals("grok-test", r.getString("model"))
        val messages = r.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("SYS", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("burrito bowl", messages.getJSONObject(1).getString("content"))
        assertEquals(XaiChat.MAX_COMPLETION_TOKENS, r.getInt("max_completion_tokens"))
        assertFalse(r.has("max_tokens")) // deprecated at xAI
        assertFalse(r.getBoolean("stream"))
        val format = r.getJSONObject("response_format")
        assertEquals("json_schema", format.getString("type"))
        val js = format.getJSONObject("json_schema")
        assertTrue(js.getBoolean("strict"))
        val schema = js.getJSONObject("schema")
        assertEquals("object", schema.getString("type"))
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(6, schema.getJSONArray("required").length())
        assertEquals("integer", schema.getJSONObject("properties").getJSONObject("kcal_low").getString("type"))
    }

    @Test fun requestWithAPhotoSendsAnImagePartThenTheText() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        val r = JSONObject(XaiChat.request("grok-test", "SYS", "burrito bowl", jpeg))
        val messages = r.getJSONArray("messages")
        assertEquals("SYS", messages.getJSONObject(0).getString("content"))
        val parts = messages.getJSONObject(1).getJSONArray("content")
        assertEquals(2, parts.length())
        val image = parts.getJSONObject(0)
        assertEquals("image_url", image.getString("type"))
        val url = image.getJSONObject("image_url").getString("url")
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertArrayEquals(jpeg, Base64.getDecoder().decode(url.removePrefix("data:image/jpeg;base64,")))
        assertEquals("high", image.getJSONObject("image_url").getString("detail"))
        assertEquals("text", parts.getJSONObject(1).getString("type"))
        assertEquals("burrito bowl", parts.getJSONObject(1).getString("text"))
        // Same structured output as text-only.
        assertTrue(r.getJSONObject("response_format").getJSONObject("json_schema").getBoolean("strict"))
    }

    @Test fun estimatorSendsThePhotoItIsGiven() = runTest {
        val t = FakeTransport { XaiReply(200, reply(estimateJson)) }
        GrokFoodEstimator({ "xai-key" }, "grok-test", t).estimate("salad", byteArrayOf(9, 9), detailed = false)
        val user = JSONObject(t.sent.single().second).getJSONArray("messages").getJSONObject(1)
        assertEquals("image_url", user.getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test fun requestEscapesUserText() {
        val text = "2 \"big\" eggs\n}{ ignore that"
        val r = JSONObject(XaiChat.request("m", "s", text))
        assertEquals(text, r.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test fun contentIsReadFromTheFirstChoice() {
        assertEquals(estimateJson, XaiChat.content(reply(estimateJson)))
    }

    @Test fun refusalEmptyCutOffOrGarbageFailPermanently() {
        assertThrows(EstimateFailed::class.java) { XaiChat.content(reply(null, refusal = "no")) }
        assertThrows(EstimateFailed::class.java) { XaiChat.content(reply(null)) }
        assertThrows(EstimateFailed::class.java) { XaiChat.content(reply("  ")) }
        assertThrows(EstimateFailed::class.java) { XaiChat.content(reply("""{"kcal_low":""", finish = "length")) }
        assertThrows(EstimateFailed::class.java) { XaiChat.content("""{"choices":[]}""") }
        assertThrows(EstimateFailed::class.java) { XaiChat.content("<html>") }
    }

    @Test fun estimateSendsKeyAndParsesReply() = runTest {
        val t = FakeTransport { XaiReply(200, reply(estimateJson)) }
        val e = GrokFoodEstimator({ "xai-secret" }, transport = t).estimate("rice", photo = null, detailed = false)
        assertEquals(FoodEstimate(650, 950, 45, 80, 28, "1 cup rice"), e)
        assertEquals("xai-secret", t.sent.single().first)
        assertEquals(GrokFoodEstimator.DEFAULT_MODEL, JSONObject(t.sent.single().second).getString("model"))
    }

    @Test fun modelIsConfigurable() = runTest {
        val t = FakeTransport { XaiReply(200, reply(estimateJson)) }
        val g = GrokFoodEstimator({ "k" }, model = "grok-4.3", transport = t)
        g.estimate("rice", photo = null, detailed = false)
        assertEquals("grok-4.3", g.model)
        assertEquals("grok-4.3", JSONObject(t.sent.single().second).getString("model"))
    }

    @Test fun noKeyAsksForAnXaiKeyWithoutCalling() = runTest {
        val t = FakeTransport { error("should not be called") }
        val e = runCatching { GrokFoodEstimator({ null }, transport = t).estimate("rice", photo = null, detailed = false) }.exceptionOrNull()
        assertTrue(e is NoApiKey)
        assertEquals(GrokFoodEstimator.NO_KEY, e!!.message)
        assertTrue(t.sent.isEmpty())
    }

    @Test fun networkErrorsAndRateLimitsRetry() = runTest {
        val t = FakeTransport { throw IOException("offline") }
        val g = GrokFoodEstimator({ "k" }, transport = t)
        assertTrue(runCatching { g.estimate("x", photo = null, detailed = false) }.exceptionOrNull() is EstimateRetryable)
        t.answer = { XaiReply(429, """{"error":"rate limited"}""") }
        assertTrue(runCatching { g.estimate("x", photo = null, detailed = false) }.exceptionOrNull() is EstimateRetryable)
        t.answer = { XaiReply(503, "") }
        assertTrue(runCatching { g.estimate("x", photo = null, detailed = false) }.exceptionOrNull() is EstimateRetryable)
    }

    @Test fun badKeyFailsWithFriendlyTextNotRawApiText() = runTest {
        val raw = """{"code":"Client specified an invalid argument","error":"Incorrect API key provided: xa***cd."}"""
        val t = FakeTransport { XaiReply(400, raw) }
        val e = runCatching { GrokFoodEstimator({ "k" }, transport = t).estimate("x", photo = null, detailed = false) }.exceptionOrNull()
        assertTrue(e is EstimateFailed)
        assertEquals(GrokErrors.BAD_KEY, e!!.message)
    }

    @Test fun grokGivesUpInItsOwnWords() {
        assertEquals(GrokErrors.GAVE_UP, GrokFoodEstimator({ "k" }).gaveUpMessage)
    }
}

class GrokErrorsTest {
    private fun msg(status: Int, body: String? = null) =
        (GrokErrors.classify(status, body, "grok-x") as EstimateErrors.Fail).message

    @Test fun mapsStatuses() {
        assertEquals(GrokErrors.BAD_KEY, msg(401))
        assertEquals("grok-x isn't available to this API key.", msg(404))
        assertTrue(msg(403).contains("permissions"))
        assertTrue(msg(400).contains("rewording"))
        assertTrue(msg(422).contains("rewording"))
        assertEquals(EstimateErrors.Retry, GrokErrors.classify(429, null, "m"))
        assertEquals(EstimateErrors.Retry, GrokErrors.classify(500, null, "m"))
        assertEquals(EstimateErrors.Retry, GrokErrors.classify(408, null, "m"))
    }

    @Test fun billingIsToldApartByMessage() {
        assertEquals(GrokErrors.NO_CREDIT, msg(403, "Your team doesn't have any credits or licenses yet."))
        assertEquals(GrokErrors.SPEND_LIMIT, msg(429, "Your team has reached its monthly spending limit."))
    }

    @Test fun unknownStatusDoesNotLeakTheBody() {
        val m = msg(418, "secret-ish server detail")
        assertFalse(m.contains("secret"))
    }
}

class SelectedFoodEstimatorTest {
    private class Named(override val model: String, override val gaveUpMessage: String) : FoodEstimator {
        var photo: ByteArray? = null
        var detailed: Boolean? = null
        override suspend fun estimate(text: String, photo: ByteArray?, detailed: Boolean, ingredients: Boolean): FoodEstimate {
            this.photo = photo
            this.detailed = detailed
            return FoodEstimate(1, 2, 0, 0, 0, text)
        }
    }

    @Test fun delegatesToTheSelectedProviderAndRecordsItsModel() = runTest {
        var selected = AiProvider.ANTHROPIC
        val s = SelectedFoodEstimator({ selected }, mapOf(
            AiProvider.ANTHROPIC to Named("claude-m", "claude gave up"),
            AiProvider.XAI to Named("grok-m", "grok gave up"),
        ))
        assertEquals("claude-m", s.estimate("a", photo = null, detailed = false).model)
        assertEquals("claude gave up", s.gaveUpMessage)
        selected = AiProvider.XAI
        assertEquals("grok-m", s.estimate("a", photo = null, detailed = false).model)
        assertEquals("grok-m", s.model)
        assertEquals("grok gave up", s.gaveUpMessage)
    }

    @Test fun passesThePhotoThrough() = runTest {
        val grok = Named("grok-m", "")
        val s = SelectedFoodEstimator({ AiProvider.XAI }, mapOf(AiProvider.ANTHROPIC to Named("c", ""), AiProvider.XAI to grok))
        val jpeg = byteArrayOf(1, 2, 3)
        s.estimate("a", jpeg, detailed = false)
        assertArrayEquals(jpeg, grok.photo)
    }

    @Test fun unknownStoredProviderFallsBackToAnthropic() {
        assertEquals(AiProvider.ANTHROPIC, AiProvider.fromId(null))
        assertEquals(AiProvider.ANTHROPIC, AiProvider.fromId("gone"))
        assertEquals(AiProvider.XAI, AiProvider.fromId("xai"))
        assertEquals(AiProvider.DEFAULT, AiProvider.ANTHROPIC)
    }
}
