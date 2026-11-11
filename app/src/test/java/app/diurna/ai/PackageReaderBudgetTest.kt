package app.diurna.ai

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A photo of several packages answers with one full product object each. The chat default
 * (1,024 tokens) cut off a real five-package photo, which the app reports as a failed read, so
 * package reads get their own budget, sized for [PackagePrompt.MAX_PRODUCTS] full products.
 */
class PackageReaderBudgetTest {
    private fun product(ingredients: String = "FLAX SEEDS.") = JSONObject().put("has_nutrition_panel", true)
        .put("product_name", "Flax Seeds").put("brand", "Roots Circle").put("size_text", "NET WT 32 OZ (2 LB) 907g")
        .put("printed_barcode", "011110766557").put("center_x", 0.5).put("serving_text", "1/2 cup uncooked (40g)")
        .put("serving_grams", 40).put("servings_per_container", 30).put("ingredients_text", ingredients).put("note", "")
        .also { p -> PackagePrompt.NUTRIENT_KEYS.forEach { p.put(it, 12.5) } }

    private val reply = JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("finish_reason", "stop")
        .put("message", JSONObject().put("content", JSONObject().put("products", JSONArray().put(product()))
            .put("questions", JSONArray()).put("note", "").toString())))).toString()

    private fun capture(): Pair<MutableList<String>, XaiTransport> {
        val sent = mutableListOf<String>()
        return sent to XaiTransport { _, json -> sent += json; XaiReply(200, reply) }
    }

    @Test fun budgetFitsEveryProductAtFullDetail() {
        // One product with every field and a long ingredients list, serialized as the model would.
        val p = product("w".repeat(600)).put("note", "n".repeat(60))
        // ~3 characters per token for JSON keys and numbers is conservative.
        val needed = (p.toString().length * PackagePrompt.MAX_PRODUCTS + 4 * 400) / 3
        assertTrue("budget ${PackagePrompt.MAX_COMPLETION_TOKENS} < needed $needed", PackagePrompt.MAX_COMPLETION_TOKENS >= needed)
        assertTrue(PackagePrompt.MAX_COMPLETION_TOKENS > XaiChat.MAX_COMPLETION_TOKENS_DETAILED)
    }

    @Test fun grokReadAndRefineSendThePackageBudget() = runTest {
        val (sent, transport) = capture()
        val r = GrokPackageReader(apiKey = { "k" }, transport = transport)
        r.read(byteArrayOf(1, 2, 3))
        r.refine(null, PackagePhotoReading(emptyList()), "It's dry oats")
        assertEquals(2, sent.size)
        sent.forEach { assertEquals(PackagePrompt.MAX_COMPLETION_TOKENS, JSONObject(it).getInt("max_completion_tokens")) }
    }

    @Test fun openAiCompatReadSendsThePackageBudget() = runTest {
        val (sent, transport) = capture()
        OpenAiCompatPackageReader(apiKey = { "k" }, modelProvider = { "m" }, transportProvider = { transport }).read(byteArrayOf(1))
        assertEquals(PackagePrompt.MAX_COMPLETION_TOKENS, JSONObject(sent.single()).getInt("max_completion_tokens"))
    }
}
