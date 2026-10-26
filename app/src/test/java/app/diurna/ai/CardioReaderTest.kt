package app.diurna.ai

import app.diurna.domain.Cardio
import app.diurna.domain.CardioPiece
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * One reader for any cardio summary screen. The Ergatta case is Larry's "15 MIN METRONOME":
 * 3,260 m, 15:07, 2:10 avg, 1:44 best, 158 W, 120 Cal, no HR. The others are a treadmill, a bike
 * and an elliptical, so nothing here is rowing-only.
 */
class CardioReaderTest {
    private fun reading(
        distance: Double = 0.0, distanceUnit: String = "none", duration: Double = 0.0, pace: Double = 0.0, paceUnit: String = "none",
        best: Double = 0.0, speed: Double = 0.0, speedUnit: String = "none", watts: Int = 0, kcal: Int = 0, hr: Int = 0, note: String = "",
    ) = JSONObject()
        .put("distance", distance).put("distance_unit", distanceUnit).put("duration_s", duration)
        .put("pace_s", pace).put("pace_unit", paceUnit).put("best_pace_s", best)
        .put("speed", speed).put("speed_unit", speedUnit)
        .put("watts", watts).put("kcal", kcal).put("avg_hr", hr).put("note", note).toString()

    private val fixture = reading(3260.0, "m", 907.0, 130.0, "500m", 104.0, watts = 158, kcal = 120, note = "15 min Metronome")
    private val fixturePiece = CardioPiece(3260, 907.0, 130.0, 104.0, 158, 120, null)

    @Test fun parsesTheErgattaSummary() {
        assertEquals(CardioReading(fixturePiece, "15 min Metronome"), CardioPrompt.parse(fixture))
    }

    @Test fun parsesATreadmillInKilometres() {
        val r = CardioPrompt.parse(reading(5.0, "km", 1560.0, 312.0, "km", kcal = 380, hr = 152, note = "Hill run"))
        assertEquals(5000, r.piece.distanceM)
        assertEquals(1560.0, r.piece.durationS!!, 1e-9)
        assertEquals(156.0, r.piece.splitS!!, 1e-9) // 5:12/km stored as s/500m
        assertEquals(380, r.piece.kcal)
        assertEquals(152, r.piece.avgHr)
        assertEquals("Hill run", r.note)
    }

    @Test fun aBikeShowingOnlySpeedGetsAPace() {
        val r = CardioPrompt.parse(reading(10.0, "mi", 2220.0, speed = 16.2, speedUnit = "mph", watts = 180, kcal = 410))
        assertEquals(16093, r.piece.distanceM)
        assertEquals(16.2, Cardio.Pace.MPH.fromSplit(r.piece.splitS!!)!!, 1e-9)
        assertEquals(180, r.piece.watts)
        assertEquals(null, r.piece.bestSplitS)
    }

    @Test fun anEllipticalHasOnlyTimeAndCalories() {
        val r = CardioPrompt.parse(reading(duration = 1800.0, kcal = 250, hr = 140))
        assertEquals(CardioPiece(durationS = 1800.0, kcal = 250, avgHr = 140), r.piece)
    }

    @Test fun aDistanceWithoutAUnitIsDropped() {
        val r = CardioPrompt.parse(reading(3.1, "none", 1500.0))
        assertEquals(CardioPiece(durationS = 1500.0), r.piece)
    }

    @Test fun zerosAndMisreadsBecomeBlank() {
        val r = CardioPrompt.parse(reading(0.0, "m", 907.4, 0.0, "500m", 99999.0, watts = -5, kcal = 120, hr = 400))
        assertEquals(CardioPiece(durationS = 907.4, kcal = 120), r.piece)
        assertEquals("", r.note)
    }

    @Test fun noDistanceOrTimeAsksToTypeIt() {
        val e = assertThrows(EstimateFailed::class.java) { CardioPrompt.parse(reading(note = "not a workout screen")) }
        assertEquals("No distance or time found (not a workout screen). Enter it by hand.", e.message)
    }

    @Test fun garbageFails() {
        assertThrows(EstimateFailed::class.java) { CardioPrompt.parse("<html>") }
        assertThrows(EstimateFailed::class.java) { CardioPrompt.parse("[1,2]") }
        assertThrows(EstimateFailed::class.java) { CardioPrompt.parse("""{"distance":"lots"}""") }
    }

    @Test fun thePromptIsNotRowingOnly() {
        listOf("treadmill", "bike", "rowing machine", "elliptical").forEach { assertTrue(it, it in CardioPrompt.SYSTEM) }
    }

    @Test fun schemaRequiresEveryField() {
        @Suppress("UNCHECKED_CAST")
        val props = CardioPrompt.SCHEMA["properties"] as Map<String, Any>
        assertEquals(props.keys.toList(), CardioPrompt.SCHEMA["required"])
        assertEquals(false, CardioPrompt.SCHEMA["additionalProperties"])
    }

    private fun reply(content: String) = JSONObject()
        .put("choices", org.json.JSONArray().put(JSONObject()
            .put("index", 0).put("finish_reason", "stop")
            .put("message", JSONObject().put("role", "assistant").put("content", content).put("refusal", JSONObject.NULL))))
        .toString()

    private class FakeTransport(var answer: () -> XaiReply) : XaiTransport {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun post(apiKey: String, json: String): XaiReply { sent += apiKey to json; return answer() }
    }

    @Test fun grokSendsThePhotoWithTheCardioSchema() = runTest {
        val t = FakeTransport { XaiReply(200, reply(fixture)) }
        val r = GrokCardioReader({ "xai-key" }, "grok-test", t).read(byteArrayOf(1, 2, 3))
        assertEquals(fixturePiece, r.piece)
        val (key, json) = t.sent.single()
        assertEquals("xai-key", key)
        val req = JSONObject(json)
        assertEquals("grok-test", req.getString("model"))
        val messages = req.getJSONArray("messages")
        assertEquals(CardioPrompt.SYSTEM, messages.getJSONObject(0).getString("content"))
        val parts = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("image_url", parts.getJSONObject(0).getString("type"))
        assertEquals(CardioPrompt.USER_TEXT, parts.getJSONObject(1).getString("text"))
        val js = req.getJSONObject("response_format").getJSONObject("json_schema")
        assertEquals("cardio_summary", js.getString("name"))
        assertTrue(js.getJSONObject("schema").getJSONObject("properties").has("pace_unit"))
    }

    @Test fun grokWithoutAKeyDoesNotCall() = runTest {
        val t = FakeTransport { error("should not be called") }
        val e = runCatching { GrokCardioReader({ null }, transport = t).read(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(e is NoApiKey)
        assertEquals(GrokCardioReader.NO_KEY, e!!.message)
    }

    @Test fun grokNetworkErrorsRetry() = runTest {
        val t = FakeTransport { throw IOException("offline") }
        assertTrue(runCatching { GrokCardioReader({ "k" }, transport = t).read(byteArrayOf(1)) }.exceptionOrNull() is EstimateRetryable)
    }

    @Test fun claudeWithoutAKeyAsksForOne() = runTest {
        val e = runCatching { ClaudeCardioReader({ null }).read(byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(e is NoApiKey)
        assertEquals("Add an Anthropic API key in Setup to read photos", e!!.message)
    }

    @Test fun claudeRequestUsesTheCardioPrompt() {
        val p = ClaudeRequest.params("claude-test", CardioPrompt.USER_TEXT, byteArrayOf(1, 2), CardioPrompt.SYSTEM, CardioPrompt.SCHEMA)
        assertEquals(CardioPrompt.SYSTEM, p.system().get().asString())
        val blocks = p.messages().single().content().asBlockParams()
        assertTrue(blocks[0].isImage())
        assertEquals(CardioPrompt.USER_TEXT, blocks[1].asText().text())
        assertTrue(p.outputConfig().get().format().isPresent)
    }

    @Test fun selectedReaderFollowsTheProvider() = runTest {
        var provider = AiProvider.XAI
        val fake = object : CardioReader {
            override val model = "fake"
            override suspend fun read(photo: ByteArray) = CardioReading(fixturePiece, "x")
        }
        val other = object : CardioReader {
            override val model = "other"
            override suspend fun read(photo: ByteArray) = error("wrong provider")
        }
        val s = SelectedCardioReader({ provider }, mapOf(AiProvider.XAI to fake, AiProvider.ANTHROPIC to other))
        assertEquals("fake", s.model)
        assertEquals(fixturePiece, s.read(byteArrayOf()).piece)
        provider = AiProvider.ANTHROPIC
        assertEquals("other", s.model)
    }
}
