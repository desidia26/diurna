package app.diurna.domain

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * What the Day screen's food box does with what was typed: plain words go to the AI estimate as
 * always; anything with a web link goes to recipe import instead, so a link is never estimated
 * as if its letters were food.
 */
object FoodLink {
    sealed interface Route {
        /** No link: the text is estimated exactly as before. */
        data class Plain(val text: String) : Route

        /**
         * [url] is imported as a recipe. If that fails, [fallback] is what may be estimated instead:
         * the words typed around the link plus the words in its path ("homemade alfredo sauce"),
         * or null when there are none (an opaque short link), so nothing is guessed from it.
         */
        data class Link(val url: String, val fallback: String?) : Route
    }

    // A scheme is required: "www.x.com" or "eggs.toast" stay plain text, so typing can't misfire.
    private val URL_RE = Regex("""(?i)\bhttps?://[^\s<>"]+""")
    private const val TRAILING = ".,;:!?)]}'\""
    private val STOP = setOf("recipe", "recipes", "html", "htm", "php", "aspx", "amp", "www", "index", "print")
    private const val MAX_FALLBACK = 120
    private val EXTENSION = Regex("""\.[A-Za-z0-9]{1,5}$""")

    fun route(text: String): Route {
        val m = URL_RE.find(text) ?: return Route.Plain(text)
        val url = m.value.trimEnd { it in TRAILING }
        val around = (text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1))
            .replace(URL_RE, " ")
            .split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
            .trim(' ', ':', '-', '–', '—', '|')
        val fallback = listOf(around, slugWords(url).orEmpty())
            .filter { it.isNotBlank() }.joinToString(" ").take(MAX_FALLBACK).trim().ifEmpty { null }
        return Route.Link(url, fallback)
    }

    /**
     * Words from the link's path, e.g. "/homemade-alfredo-sauce-recipe/" → "homemade alfredo sauce".
     * Only a segment with at least two real words counts, so "/GiG2l" or "/p/12345" give null.
     * The query and fragment are never read: they carry tracking and share tokens, not food.
     */
    fun slugWords(url: String): String? {
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: return null
        return path.split('/').asReversed().asSequence()
            .map { seg -> runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg).replace(EXTENSION, "") }
            .map { seg ->
                seg.split('-', '_', '+', ' ')
                    .map { it.lowercase(Locale.US) }
                    .filter { w -> w.length >= 2 && w.all { it.isLetter() } && w !in STOP }
            }
            .firstOrNull { it.size >= 2 }
            ?.joinToString(" ")
    }
}
