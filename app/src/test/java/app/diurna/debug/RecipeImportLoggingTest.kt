package app.diurna.debug

import app.diurna.data.recipe.FetchedPage
import app.diurna.data.recipe.RecipeImportResult
import app.diurna.data.recipe.RecipeImporter
import app.diurna.data.recipe.SafeHttp
import app.diurna.data.recipe.SafeHttpException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class RecipeImportLoggingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val recipePage = """<html><head><script type="application/ld+json">{"@type":"Recipe","name":"Chili",
        "recipeIngredient":["1 can beans","2 cups broth","1 onion"]}</script></head></html>"""
    private val url = "https://www.Example.com/recipes/chili/?utm_source=share&token=abc123#step-2"

    // Journal files sort by millisecond then a random id, so pick entries by role, not position.
    private fun start(log: List<JSONObject>) = log.single { it.getString("action") == "recipe_import" && it.getString("result") == "STARTED" }
    private fun end(log: List<JSONObject>) = log.single { it.getString("action") == "recipe_import" && it.getString("result") != "STARTED" }

    private fun recorder(typed: Boolean) = ActionRecorder(tmp.newFolder()).apply { enabled = true; includeTypedText = typed }

    private fun run(rec: ActionRecorder, fetch: suspend (String) -> FetchedPage): Pair<RecipeImportResult, List<JSONObject>> {
        var t = 0L
        val logged = LoggedRecipeImporter(RecipeImporter(fetch), rec.store, nanoTime = { t.also { t += 250_000_000 } })
        val r = runBlocking { logged.importFromUrl(url) }
        return r to rec.snapshot().map { JSONObject(it) }
    }

    @Test fun successLogsHostOutcomeSizeIngredientsAndDuration() {
        val rec = recorder(typed = false)
        val (r, log) = run(rec) { SafeHttp.readCapped(ByteArrayInputStream(recipePage.toByteArray())) }
        assertTrue(r is RecipeImportResult.Ok)
        assertEquals(2, log.size)
        val start = start(log); val end = end(log)
        assertEquals("recipe_import", start.getString("action"))
        assertEquals("STARTED", start.getString("result"))
        assertEquals("LIBRARY", start.getString("screen"))
        assertEquals("www.example.com", start.getJSONObject("details").getString("host"))
        assertEquals(start.getString("correlationId"), end.getString("correlationId"))
        assertEquals("OK", end.getString("result"))
        assertEquals(250L, end.getLong("latencyMs"))
        val d = end.getJSONObject("details")
        assertEquals("ok", d.getString("outcome"))
        assertEquals("3", d.getString("ingredients"))
        assertEquals(recipePage.toByteArray().size.toString(), d.getString("bytes"))
        assertFalse(d.has("truncated"))
        val all = log.joinToString("\n")
        assertFalse("path must need typed-text logging", all.contains("/recipes/chili"))
        assertFalse(all.contains("token")); assertFalse(all.contains("abc123")); assertFalse(all.contains("utm_source"))
    }

    @Test fun typedTextLoggingAddsPathButNeverQuery() {
        val rec = recorder(typed = true)
        val (_, log) = run(rec) { SafeHttp.readCapped(ByteArrayInputStream(recipePage.toByteArray())) }
        val text = log.single { it.getString("action") == "field.text" }
        assertEquals("URL", text.getString("field"))
        assertEquals("www.example.com/recipes/chili/", text.getString("text"))
        val all = log.joinToString("\n")
        assertFalse(all.contains("abc123")); assertFalse(all.contains("utm_source")); assertFalse(all.contains("step-2"))
    }

    @Test fun noRecipeLogsReasonAndTruncation() {
        val rec = recorder(typed = false)
        val (r, log) = run(rec) { FetchedPage("<html>nothing</html>", 2_097_152, truncated = true) }
        assertTrue(r is RecipeImportResult.Problem)
        val d = end(log).getJSONObject("details")
        assertEquals("FAILED", end(log).getString("result"))
        assertEquals("no_recipe", d.getString("outcome"))
        assertEquals("2097152", d.getString("bytes"))
        assertEquals("true", d.getString("truncated"))
        assertFalse(d.has("ingredients"))
    }

    @Test fun httpFailureLogsStatusNotMessage() {
        val rec = recorder(typed = false)
        val (r, log) = run(rec) { throw SafeHttpException("HTTP 403 secret-ish detail", SafeHttpException.Reason.HTTP_STATUS, 403) }
        assertEquals("HTTP 403 secret-ish detail", (r as RecipeImportResult.Problem).message)
        val d = end(log).getJSONObject("details")
        assertEquals("http_status", d.getString("outcome"))
        assertEquals("403", d.getString("http_status"))
        assertFalse(log.joinToString("\n").contains("secret-ish"))
    }

    @Test fun networkTimeoutAndBlockedReasons() {
        for ((reason, token) in listOf(
            SafeHttpException.Reason.NETWORK to "network", SafeHttpException.Reason.TIMEOUT to "timeout",
            SafeHttpException.Reason.BLOCKED_HOST to "blocked_host", SafeHttpException.Reason.DNS to "dns",
            SafeHttpException.Reason.INVALID_URL to "invalid_url", SafeHttpException.Reason.REDIRECTS to "redirects",
        )) {
            val (_, log) = run(recorder(typed = false)) { throw SafeHttpException("x", reason) }
            assertEquals(token, end(log).getJSONObject("details").getString("outcome"))
        }
        val (_, log) = run(recorder(typed = false)) { throw IllegalStateException("boom") }
        assertEquals("network", end(log).getJSONObject("details").getString("outcome"))
    }

    @Test fun nothingLoggedWhenDebugOff() {
        val rec = ActionRecorder(tmp.newFolder())
        val (r, log) = run(rec) { SafeHttp.readCapped(ByteArrayInputStream(recipePage.toByteArray())) }
        assertTrue(r is RecipeImportResult.Ok)
        assertTrue(log.isEmpty())
    }

    @Test fun urlParts() {
        assertEquals(RecipeUrlParts("www.example.com", "/r/1"), recipeUrlParts(" https://user:pw@WWW.Example.COM.:8443/r/1?x=1#y "))
        val idn = recipeUrlParts("https://crème-brûle.example/")
        assertTrue(idn.host, idn.host.startsWith("xn--") && idn.host.endsWith(".example"))
        assertNull(idn.path)
        assertEquals("invalid", recipeUrlParts("not a url").host)
        assertEquals("invalid", recipeUrlParts("").host)
        assertNull(recipeUrlParts("https://example.com").path)
    }

    @Test fun recorderDropsUnknownDetailKeys() {
        val rec = recorder(typed = false)
        rec.store.dispatch(Action.Event(Screen.LIBRARY, "recipe_import", details = mapOf("host" to "a.com", "url" to "https://a.com/x?t=1")))
        val d = JSONObject(rec.snapshot().single()).getJSONObject("details")
        assertEquals("a.com", d.getString("host"))
        assertFalse(d.has("url"))
    }
}
