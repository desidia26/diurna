package app.diurna.ai

import app.diurna.debug.ActionRecorder
import app.diurna.debug.LoggedFoodEstimator
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

/**
 * Regression for debug report ea12351c (0.261): "3 links" with a barcode photo and a Nutrition
 * Facts photo (60 kcal per link) was estimated from the barcode photo alone, because only an
 * entry's first photo went to the model. Every photo now goes, in gallery order.
 */
class EstimateAllPhotosTest {
    @get:Rule val tmp = TemporaryFolder()

    private val barcode = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1)
    private val label = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 2)
    private val estimateJson = """{"kcal_low":170,"kcal_high":190,"protein_g":24,"carbs_g":6,"fat_g":6,"note":"3 links at 60 kcal"}"""

    private fun reply() = JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
        .put("finish_reason", "stop")
        .put("message", JSONObject().put("role", "assistant").put("content", estimateJson).put("refusal", JSONObject.NULL))))
        .toString()

    private class FakeTransport(val body: String) : XaiTransport {
        val sent = mutableListOf<String>()
        override suspend fun post(apiKey: String, json: String): XaiReply { sent += json; return XaiReply(200, body) }
    }

    private fun imagesIn(requestJson: String): List<ByteArray> {
        val parts = JSONObject(requestJson).getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        return (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.getString("type") == "image_url" }
            .map { Base64.getDecoder().decode(it.getJSONObject("image_url").getString("url").removePrefix("data:image/jpeg;base64,")) }
    }

    @Test fun grokSendsEveryPhotoInOrderThenTheText() = runTest {
        val t = FakeTransport(reply())
        GrokFoodEstimator({ "k" }, "grok-test", t).estimatePhotos("3 links", listOf(barcode, label))
        val sent = imagesIn(t.sent.single())
        assertEquals(2, sent.size)
        assertArrayEquals(barcode, sent[0]); assertArrayEquals(label, sent[1])
        val parts = JSONObject(t.sent.single()).getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("3 links", parts.getJSONObject(parts.length() - 1).getString("text"))
    }

    @Test fun openAiCompatibleSendsEveryPhoto() = runTest {
        val t = FakeTransport(reply())
        OpenAiCompatFoodEstimator(apiKey = { "" }, modelProvider = { "m" }, transportProvider = { t })
            .estimatePhotos("3 links", listOf(barcode, label))
        assertEquals(2, imagesIn(t.sent.single()).size)
    }

    @Test fun claudeSendsEveryPhotoBeforeTheText() {
        val p = ClaudeRequest.params("claude-test", "3 links", photos = listOf(barcode, label))
        val blocks = p.messages().single().content().asContentBlockParams()
        assertEquals(3, blocks.size)
        assertTrue(blocks[0].isImage()); assertTrue(blocks[1].isImage()); assertTrue(blocks[2].isText())
        assertArrayEquals(label, Base64.getDecoder().decode(
            blocks[1].asImage().source().asBase64().data()))
    }

    @Test fun noVisionSendsNoPhotosAndSaysSo() = runTest {
        val t = FakeTransport(reply())
        val e = GrokFoodEstimator({ "k" }, "grok-test", t, supportsImages = { false }).estimatePhotos("3 links", listOf(barcode, label))
        assertEquals("3 links", JSONObject(t.sent.single()).getJSONArray("messages").getJSONObject(1).getString("content"))
        assertTrue(e.note != "3 links at 60 kcal")
    }

    @Test fun photosAreCappedAtTheGalleryLimit() = runTest {
        val t = FakeTransport(reply())
        GrokFoodEstimator({ "k" }, "grok-test", t).estimatePhotos("x", List(7) { byteArrayOf(it.toByte()) })
        assertEquals(VisionSupport.MAX_ESTIMATE_PHOTOS, imagesIn(t.sent.single()).size)
    }

    private class Recording : FoodEstimator {
        override val model = "rec"
        var got: List<ByteArray>? = null
        override suspend fun estimate(text: String, photo: ByteArray?, detailed: Boolean, ingredients: Boolean) =
            FoodEstimate(1, 2, 0, 0, 0, "n").also { got = listOfNotNull(photo) }
        override suspend fun estimatePhotos(text: String, photos: List<ByteArray>, detailed: Boolean, ingredients: Boolean) =
            FoodEstimate(1, 2, 0, 0, 0, "n").also { got = photos }
    }

    @Test fun selectedAndLoggedEstimatorsPassEveryPhotoThrough() = runTest {
        val rec = Recording()
        val recorder = ActionRecorder(tmp.newFolder()).apply { enabled = true }
        val chain = LoggedFoodEstimator(SelectedFoodEstimator({ AiProvider.XAI }, mapOf(AiProvider.XAI to rec)), recorder.store)
        chain.estimatePhotos("3 links", listOf(barcode, label))
        assertEquals(2, rec.got!!.size)
        val started = recorder.snapshot().map { JSONObject(it) }.single { it.getString("result") == "STARTED" }
        assertEquals("ai.food", started.getString("action"))
        assertEquals("2", started.getJSONObject("details").getString("photos"))
        assertTrue(recorder.snapshot().none { it.contains("base64") })
    }

    @Test fun nothingIsLoggedWithDebugLoggingOff() = runTest {
        val rec = Recording()
        val recorder = ActionRecorder(tmp.newFolder())
        LoggedFoodEstimator(rec, recorder.store).estimatePhotos("3 links", listOf(barcode, label))
        assertEquals(2, rec.got!!.size)
        assertTrue(recorder.snapshot().isEmpty())
    }

    @Test fun entryPhotoNamesUseTheGalleryInOrderElseThePrimary() {
        assertEquals(listOf("barcode.jpg", "label.jpg"), EntryPhotos.names(listOf("barcode.jpg", "label.jpg"), "barcode.jpg"))
        assertEquals(listOf("old.jpg"), EntryPhotos.names(emptyList(), "old.jpg"))
        assertEquals(emptyList<String>(), EntryPhotos.names(emptyList(), null))
        assertEquals(VisionSupport.MAX_ESTIMATE_PHOTOS, EntryPhotos.names(List(9) { "p$it.jpg" }, null).size)
    }
}
