package app.diurna.domain

import app.diurna.domain.FoodLink.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodLinkTest {
    private fun link(text: String) = FoodLink.route(text) as Route.Link

    @Test fun bareShortLinkImportsAndHasNothingToEstimate() {
        // Report r1119: an opaque share link was estimated as food ("nonsense").
        val r = link("https://s.samsungfood.com/GiG2l")
        assertEquals("https://s.samsungfood.com/GiG2l", r.url)
        assertNull(r.fallback)
    }

    @Test fun surroundingWhitespaceIsIgnored() {
        val r = link("  \thttps://s.samsungfood.com/GiG2l \n")
        assertEquals("https://s.samsungfood.com/GiG2l", r.url)
        assertNull(r.fallback)
    }

    @Test fun recipePageLinkKeepsTrackingParamsForTheFetchButNotInTheFallback() {
        // Report r1119b: the slug is what made the text estimate sensible.
        val url = "https://snappygourmet.com/homemade-alfredo-sauce-recipe/?utm_source=whisk&utm_medium=android&utm_campaign=homemade_alfredo_sauce"
        val r = link(url)
        assertEquals(url, r.url)
        assertEquals("homemade alfredo sauce", r.fallback)
    }

    @Test fun wordsAroundALinkStillImportAndJoinTheFallback() {
        // Share sheets paste "Title https://…"; the link is the point, the words help a fallback.
        val r = link("Check out Homemade Alfredo Sauce: https://s.samsungfood.com/GiG2l")
        assertEquals("https://s.samsungfood.com/GiG2l", r.url)
        assertEquals("Check out Homemade Alfredo Sauce", r.fallback)
        val r2 = link("half of https://example.com/recipes/chicken-tikka-masala.html, with rice")
        assertEquals("https://example.com/recipes/chicken-tikka-masala.html", r2.url)
        assertEquals("half of with rice chicken tikka masala", r2.fallback)
    }

    @Test fun trailingPunctuationIsNotPartOfTheLink() {
        assertEquals("https://example.com/r/beef-stew", link("(https://example.com/r/beef-stew).").url)
    }

    @Test fun httpLinksRouteToImportToo() {
        // The importer refuses http with a clear message; it still must never be estimated as text.
        val r = link("http://example.com/x/9f8e7d")
        assertEquals("http://example.com/x/9f8e7d", r.url)
        assertNull(r.fallback)
    }

    @Test fun opaqueOrNumericPathsGiveNoWords() {
        assertNull(FoodLink.slugWords("https://example.com/p/123456"))
        assertNull(FoodLink.slugWords("https://example.com/"))
        assertNull(FoodLink.slugWords("https://example.com"))
        assertNull(FoodLink.slugWords("https://example.com/recipe/"))
        assertNull(FoodLink.slugWords("https://example.com/a1b2-c3d4"))
    }

    @Test fun slugWordsComeFromTheLastWordyPathSegment() {
        assertEquals("beef stew", FoodLink.slugWords("https://example.com/recipes/12345/beef-stew/"))
        assertEquals("pad thai", FoodLink.slugWords("https://example.com/recipes/pad_thai/98765"))
        assertEquals("crème brûlée", FoodLink.slugWords("https://example.com/cr%C3%A8me-br%C3%BBl%C3%A9e"))
        assertNull("query never read", FoodLink.slugWords("https://example.com/r/77?name=beef-stew"))
    }

    @Test fun plainTextIsUnchanged() {
        listOf(
            "1 cup tiramisu", "2 eggs and toast", "", "   ", "eggs.toast", "www.example.com/beef-stew",
            "https is a protocol", "chicken http bowl", "Café au lait: 12 oz",
        ).forEach { assertEquals(Route.Plain(it), FoodLink.route(it)) }
    }
}
