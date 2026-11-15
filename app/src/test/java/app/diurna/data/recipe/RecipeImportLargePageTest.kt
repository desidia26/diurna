package app.diurna.data.recipe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Regression for debug report d2ed150c (0.261): "page is too large when importing recipe".
 * Big recipe sites serve 0.5-1.1 MB of HTML (Allrecipes ~1.1 MB with its JSON-LD ending
 * ~512 KB in; Love and Lemons ~620 KB, BBC Good Food ~560 KB with the recipe in the first
 * 80 KB). The old 512 KiB cap threw "Page is too large" before parsing anything.
 */
class RecipeImportLargePageTest {
    private val recipeLd = """
        <script type="application/ld+json">{"@context":"https://schema.org","@type":"Recipe",
        "name":"Easy Meatloaf","recipeYield":"8",
        "recipeIngredient":["1 1/2 pounds ground beef","1 egg","1 cup milk"]}</script>
    """.trimIndent()

    private fun page(before: Int, after: Int): ByteArray {
        val filler = { n: Int -> "<div class=\"ad\">" + "x".repeat(maxOf(0, n - 22)) + "</div>\n" }
        return ("<html><head>" + filler(before) + recipeLd + "</head><body>" + filler(after) + "</body></html>")
            .toByteArray(Charsets.UTF_8)
    }

    private fun importBytes(bytes: ByteArray): RecipeImportResult = runBlocking {
        RecipeImporter(fetch = { SafeHttp.readCapped(ByteArrayInputStream(bytes)) })
            .importFromUrl("https://example.com/recipe/16354/easy-meatloaf/")
    }

    @Test fun allrecipesSizedPageImports() {
        // Shape of the Allrecipes page: JSON-LD starts ~493 KB in, page is ~1.1 MB.
        val r = importBytes(page(before = 493_000, after = 600_000))
        assertTrue("expected Ok, got $r", r is RecipeImportResult.Ok)
        assertEquals("Easy Meatloaf", (r as RecipeImportResult.Ok).recipe.name)
        assertEquals(3, r.recipe.ingredients.size)
    }

    @Test fun recipeNearTopOfLargePageImports() {
        // Love and Lemons / BBC Good Food shape: recipe early, page 550-620 KB.
        val r = importBytes(page(before = 70_000, after = 550_000))
        assertTrue("expected Ok, got $r", r is RecipeImportResult.Ok)
    }

    @Test fun recipeDeepInMultiMegabytePageImports() {
        val r = importBytes(page(before = 1_500_000, after = 3_000_000))
        assertTrue("expected Ok, got $r", r is RecipeImportResult.Ok)
    }

    @Test fun readCappedStopsAtCapInsteadOfFailing() {
        val huge = ByteArray(SafeHttp.MAX_BYTES * 3) { 'a'.code.toByte() }
        val page = SafeHttp.readCapped(ByteArrayInputStream(huge))
        assertEquals(SafeHttp.MAX_BYTES, page.body.length)
        assertEquals(SafeHttp.MAX_BYTES, page.bytes)
        assertTrue(page.truncated)
    }

    @Test fun recipePastTheCapIsNotFound() {
        // Memory stays bounded: content beyond the cap is never read.
        val r = importBytes(page(before = SafeHttp.MAX_BYTES + 10_000, after = 1_000))
        assertTrue("expected Problem, got $r", r is RecipeImportResult.Problem)
    }

    @Test fun unquotedJsonLdTypeAttributeImports() {
        // Minified WordPress pages (e.g. Love and Lemons, ~620 KB) drop the attribute quotes:
        // <script type=application/ld+json class=yoast-schema-graph>
        val html = "<html><head><script type=application/ld+json class=yoast-schema-graph>" +
            "{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"Recipe\"," +
            "\"name\":\"Vegetarian Chili\",\"recipeIngredient\":[\"1 can black beans\",\"2 cups broth\"]}]}" +
            "</script></head><body></body></html>"
        val r = parseRecipeHtml(html, "https://example.com/vegetarian-chili/")
        assertEquals("Vegetarian Chili", r?.name)
    }

    @Test fun manyUnclosedJsonLdTagsStayFast() {
        // A hostile page of unclosed ld+json tags must not make extraction quadratic.
        val tag = "<script type=\"application/ld+json\">{"
        val html = tag.repeat(SafeHttp.MAX_BYTES / tag.length)
        val t0 = System.nanoTime()
        assertEquals(null, parseRecipeHtml(html, "https://example.com/x"))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 2_000)
    }
}
