package app.diurna.data.recipe

import app.diurna.domain.IngredientUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeImportTest {
    private val sampleLd = """
        {
          "@context": "https://schema.org",
          "@type": "Recipe",
          "name": "Banana Bread",
          "description": "A simple loaf.",
          "recipeYield": "8 servings",
          "recipeIngredient": [
            "2 cups all-purpose flour",
            "1/2 cup sugar",
            "3 ripe bananas",
            "100 g butter"
          ]
        }
    """.trimIndent()

    @Test fun jsonLdBasic() {
        val r = parseRecipeJsonLd(sampleLd, "https://example.com/banana-bread")!!
        assertEquals("Banana Bread", r.name)
        assertEquals(8.0, r.yield, 0.0)
        assertEquals(4, r.ingredients.size)
        assertEquals(IngredientUnit.CUP, r.ingredients[0].unit)
        assertEquals(2.0, r.ingredients[0].amount, 0.0)
        assertEquals("all-purpose flour", r.ingredients[0].name)
        assertEquals(IngredientUnit.CUP, r.ingredients[1].unit)
        assertEquals(0.5, r.ingredients[1].amount, 0.0)
        assertEquals(IngredientUnit.EACH, r.ingredients[2].unit)
        assertEquals("ripe bananas", r.ingredients[2].name)
        assertEquals(IngredientUnit.G, r.ingredients[3].unit)
        assertEquals(100.0, r.ingredients[3].amount, 0.0)
        assertTrue(r.toMealDraft().note!!.contains("https://example.com/banana-bread"))
    }

    @Test fun jsonLdInHtmlScript() {
        val html = """
            <html><head>
            <script type="application/ld+json">$sampleLd</script>
            </head><body><p>ignore me</p></body></html>
        """.trimIndent()
        val r = parseRecipeHtml(html, "https://example.com/r")!!
        assertEquals("Banana Bread", r.name)
    }

    @Test fun jsonLdGraph() {
        val json = """
            {
              "@context":"https://schema.org",
              "@graph":[
                {"@type":"WebPage","name":"Page"},
                {"@type":"Recipe","name":"Oatmeal","recipeIngredient":["1 cup oats"],"recipeYield":2}
              ]
            }
        """.trimIndent()
        val r = parseRecipeJsonLd(json)!!
        assertEquals("Oatmeal", r.name)
        assertEquals(2.0, r.yield, 0.0)
    }

    @Test fun jsonLdArrayRoot() {
        val json = """[
          {"@type":"Recipe","name":"Soup","recipeIngredient":["1 L water","2 tsp salt"],"recipeYield":"4"}
        ]"""
        val r = parseRecipeJsonLd(json)!!
        assertEquals("Soup", r.name)
        assertEquals(IngredientUnit.TSP, r.ingredients[1].unit)
    }

    @Test fun microdataFallback() {
        val html = """
            <div itemscope itemtype="https://schema.org/Recipe">
              <h1 itemprop="name">Pancakes</h1>
              <span itemprop="recipeYield">4</span>
              <ul>
                <li itemprop="recipeIngredient">1 cup flour</li>
                <li itemprop="recipeIngredient">2 eggs</li>
              </ul>
            </div>
        """.trimIndent()
        val r = parseRecipeHtml(html, "https://example.com/pancakes")!!
        assertEquals("Pancakes", r.name)
        assertEquals(4.0, r.yield, 0.0)
        assertEquals(2, r.ingredients.size)
        assertEquals("flour", r.ingredients[0].name)
    }

    @Test fun noRecipeReturnsNull() {
        assertNull(parseRecipeHtml("<html><body>Hello</body></html>", "https://example.com"))
        assertNull(parseRecipeJsonLd("""{"@type":"Article","name":"News"}"""))
        assertNull(parseRecipeJsonLd("not json"))
        assertNull(parseRecipeHtml("", "https://example.com"))
    }

    @Test fun ingredientLineEdgeCases() {
        assertEquals(1.5, parseIngredientLine("1 1/2 cups milk").amount, 0.0)
        assertEquals(IngredientUnit.CUP, parseIngredientLine("1 1/2 cups milk").unit)
        assertEquals("milk", parseIngredientLine("1 1/2 cups milk").name)
        assertEquals(IngredientUnit.TBSP, parseIngredientLine("2 tablespoons olive oil").unit)
        assertEquals(IngredientUnit.ML, parseIngredientLine("250 ml water").unit)
        // no amount → 1 each
        val bare = parseIngredientLine("pinch of salt")
        assertEquals(1.0, bare.amount, 0.0)
        assertEquals(IngredientUnit.EACH, bare.unit)
    }

    @Test fun hostileTextIsSanitized() {
        val json = """
            {
              "@type":"Recipe",
              "name":"Bad\u0000Name\n\twith\u202Eoverride",
              "recipeIngredient":["2 cups flour\u0007"]
            }
        """.trimIndent()
        val r = parseRecipeJsonLd(json)!!
        assertFalseControl(r.name)
        assertFalseControl(r.ingredients[0].raw)
        assertTrue(r.name.length <= 120)
    }

    private fun assertFalseControl(s: String) {
        assertTrue(s.none { it.isISOControl() })
        assertTrue(s.none { Character.getType(it) == Character.FORMAT.toInt() })
    }

    @Test fun yieldParsing() {
        assertEquals(4.0, parseYield("4 servings")!!, 0.0)
        assertEquals(6.0, parseYield(6)!!, 0.0)
        assertEquals(8.0, parseYield("Serves 8")!!, 0.0)
        assertNull(parseYield(-3))
        assertNull(parseYield(0))
        assertNull(parseYield("none"))
    }

    @Test fun missingIngredientsRejected() {
        assertNull(parseRecipeJsonLd("""{"@type":"Recipe","name":"Empty","recipeIngredient":[]}"""))
        assertNull(parseRecipeJsonLd("""{"@type":"Recipe","name":"Empty"}"""))
    }

    @Test fun importerUsesFetcherAndMapsErrors() = runBlocking {
        val ok = RecipeImporter { sampleHtml() }.importFromUrl("https://example.com/r")
        assertTrue(ok is RecipeImportResult.Ok)
        assertEquals("Banana Bread", (ok as RecipeImportResult.Ok).recipe.name)

        val blocked = RecipeImporter { throw SafeHttpException("That address is not allowed") }
            .importFromUrl("https://127.0.0.1/r")
        assertTrue(blocked is RecipeImportResult.Problem)
        assertEquals("That address is not allowed", (blocked as RecipeImportResult.Problem).message)

        val none = RecipeImporter { "<html></html>" }.importFromUrl("https://example.com/r")
        assertTrue(none is RecipeImportResult.Problem)
    }

    @Test fun fuzzGarbageDoesNotThrow() {
        val junk = listOf(
            "",
            "<",
            "<script type=\"application/ld+json\">",
            "<script type=\"application/ld+json\">{",
            "<script type=\"application/ld+json\">null</script>",
            "<script type=\"application/ld+json\">[]</script>",
            "<script type=\"application/ld+json\">{\"@type\":[]}</script>",
            "<script type=\"application/ld+json\">" + "x".repeat(10_000) + "</script>",
            """{"@type":"Recipe","name":""" + "A".repeat(500) + ""","recipeIngredient":[""" +
                (1..200).joinToString(",") { "\"$it cups flour\"" } + "]}",
            """{"@type":"Recipe","name":"X","recipeIngredient":["NaN cups flour","Infinity g sugar","-5 cup oil"]}""",
        )
        junk.forEach { sample ->
            // Must not throw
            parseRecipeHtml(sample, "https://example.com/r")
            parseRecipeJsonLd(sample)
        }
    }

    @Test fun mixedNumberAndTbspAliases() {
        assertEquals(IngredientUnit.TBSP, parseIngredientLine("1 tbs butter").unit)
        assertEquals(IngredientUnit.TSP, parseIngredientLine("1 tsp. vanilla").unit)
        assertEquals("vanilla", parseIngredientLine("1 tsp. vanilla").name)
    }

    private fun sampleHtml() = """
        <html><head><script type="application/ld+json">$sampleLd</script></head></html>
    """.trimIndent()
}
