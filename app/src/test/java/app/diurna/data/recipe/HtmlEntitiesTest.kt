package app.diurna.data.recipe

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlEntitiesTest {
    @Test fun namedDecimalAndHex() {
        assertEquals("Mac & Cheese", decodeHtmlEntities("Mac &amp; Cheese"))
        assertEquals("Grandma's pie", decodeHtmlEntities("Grandma&#39;s pie"))
        assertEquals("Grandma's pie", decodeHtmlEntities("Grandma&#x27;s pie"))
        assertEquals("Grandma's pie", decodeHtmlEntities("Grandma&#X27;s pie"))
        assertEquals("1 cup flour", decodeHtmlEntities("1&nbsp;cup flour"))
        assertEquals("“Best” crème brûlée – easy…", decodeHtmlEntities("&ldquo;Best&rdquo; cr&egrave;me br&ucirc;l&eacute;e &ndash; easy&hellip;"))
        assertEquals("<b> \"q\"", decodeHtmlEntities("&lt;b&gt; &quot;q&quot;"))
    }

    @Test fun decodesExactlyOnce() {
        assertEquals("&amp;", decodeHtmlEntities("&amp;amp;"))
        assertEquals("&lt;script&gt;", decodeHtmlEntities("&amp;lt;script&amp;gt;"))
        assertEquals("&#39;", decodeHtmlEntities("&amp;#39;"))
    }

    @Test fun leavesUnknownAndMalformedAlone() {
        assertEquals("AT&T and &bogus; and & alone", decodeHtmlEntities("AT&T and &bogus; and & alone"))
        assertEquals("&#;", decodeHtmlEntities("&#;"))
        assertEquals("&#xZZ;", decodeHtmlEntities("&#xZZ;"))
    }

    @Test fun invalidCodePointsBecomeReplacement() {
        assertEquals("a\uFFFDb", decodeHtmlEntities("a&#0;b"))
        assertEquals("a\uFFFDb", decodeHtmlEntities("a&#xD800;b"))
        assertEquals("a\uFFFDb", decodeHtmlEntities("a&#x110000;b"))
        assertEquals("a\uFFFDb", decodeHtmlEntities("a&#99999999999;b"))
        assertEquals("\uD83C\uDF55", decodeHtmlEntities("&#x1F355;"))
    }

    @Test fun simplyRecipesPizzaName() {
        val ld = """{"@context":"https://schema.org","@type":"Recipe",
            "name":"Homemade Pizza &amp; Pizza Dough","description":"Chef&#39;s &quot;best&quot; crust",
            "recipeYield":"2 pizzas",
            "recipeIngredient":["1&nbsp;1/2 cups warm water","Salt &amp; pepper","2 tablespoons olive oil &#x2014; divided"]}"""
        val r = parseRecipeJsonLd(ld)!!
        assertEquals("Homemade Pizza & Pizza Dough", r.name)
        assertEquals("Chef's \"best\" crust", r.description)
        assertEquals("Salt & pepper", r.ingredients[1].raw)
        assertEquals(1.5, r.ingredients[0].amount, 0.0)
        assertEquals("2 tablespoons olive oil — divided", r.ingredients[2].raw)
    }

    @Test fun microdataTextIsDecodedOnce() {
        val html = """<div itemscope itemtype="https://schema.org/Recipe">
            <h1 itemprop="name">Beans &amp;amp; Rice</h1>
            <li itemprop="recipeIngredient">1 can beans &amp; liquid</li></div>"""
        val r = parseRecipeHtml(html, "https://example.com/r")!!
        assertEquals("Beans &amp; Rice", r.name)
        assertEquals("1 can beans & liquid", r.ingredients[0].raw)
    }
}
