package app.diurna.data.recipe

/**
 * Decodes HTML character references in text taken from recipe pages, in one pass: every
 * reference in the input is replaced once and the output is never scanned again, so
 * `&amp;lt;` becomes the literal text `&lt;`, not `<`. Unknown names and malformed references
 * stay as written. Numeric references outside Unicode, surrogates and NUL become U+FFFD.
 * No-break spaces become plain spaces so amounts like "1&nbsp;1/2" still parse.
 * The result is plain text for display; it is never rendered as HTML.
 */
fun decodeHtmlEntities(s: String): String {
    if (s.indexOf('&') < 0) return s
    return ENTITY_RE.replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toLongOrNull(16))
            body.startsWith("#") -> codePoint(body.substring(1).toLongOrNull())
            else -> NAMED[body] ?: m.value
        }
    }
}

private val ENTITY_RE = Regex("""&(#[0-9]{1,12}|#[xX][0-9a-fA-F]{1,10}|[A-Za-z][A-Za-z0-9]{1,31});""")

private fun codePoint(n: Long?): String = when {
    n == null || n <= 0L || n > 0x10FFFFL || n in 0xD800L..0xDFFFL -> "\uFFFD"
    n == 0xA0L -> " "
    else -> String(Character.toChars(n.toInt()))
}

private val NAMED: Map<String, String> = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
    "ndash" to "\u2013", "mdash" to "\u2014", "hellip" to "\u2026", "bull" to "\u2022", "middot" to "\u00B7",
    "lsquo" to "\u2018", "rsquo" to "\u2019", "sbquo" to "\u201A", "ldquo" to "\u201C", "rdquo" to "\u201D",
    "bdquo" to "\u201E", "laquo" to "\u00AB", "raquo" to "\u00BB", "prime" to "\u2032", "Prime" to "\u2033",
    "frac12" to "\u00BD", "frac14" to "\u00BC", "frac34" to "\u00BE", "frac13" to "\u2153", "frac23" to "\u2154",
    "frac18" to "\u215B", "frac38" to "\u215C", "frac58" to "\u215D", "frac78" to "\u215E", "frasl" to "\u2044",
    "deg" to "\u00B0", "times" to "\u00D7", "divide" to "\u00F7", "plusmn" to "\u00B1", "minus" to "\u2212",
    "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122", "cent" to "\u00A2", "pound" to "\u00A3",
    "euro" to "\u20AC", "yen" to "\u00A5", "sect" to "\u00A7", "para" to "\u00B6", "micro" to "\u00B5",
    "shy" to "", "zwnj" to "", "zwj" to "",
    "iexcl" to "\u00A1", "iquest" to "\u00BF", "ordf" to "\u00AA", "ordm" to "\u00BA", "sup1" to "\u00B9",
    "sup2" to "\u00B2", "sup3" to "\u00B3", "szlig" to "\u00DF",
    "Agrave" to "\u00C0", "Aacute" to "\u00C1", "Acirc" to "\u00C2", "Atilde" to "\u00C3", "Auml" to "\u00C4",
    "Aring" to "\u00C5", "AElig" to "\u00C6", "Ccedil" to "\u00C7", "Egrave" to "\u00C8", "Eacute" to "\u00C9",
    "Ecirc" to "\u00CA", "Euml" to "\u00CB", "Igrave" to "\u00CC", "Iacute" to "\u00CD", "Icirc" to "\u00CE",
    "Iuml" to "\u00CF", "Ntilde" to "\u00D1", "Ograve" to "\u00D2", "Oacute" to "\u00D3", "Ocirc" to "\u00D4",
    "Otilde" to "\u00D5", "Ouml" to "\u00D6", "Oslash" to "\u00D8", "Ugrave" to "\u00D9", "Uacute" to "\u00DA",
    "Ucirc" to "\u00DB", "Uuml" to "\u00DC", "Yacute" to "\u00DD",
    "agrave" to "\u00E0", "aacute" to "\u00E1", "acirc" to "\u00E2", "atilde" to "\u00E3", "auml" to "\u00E4",
    "aring" to "\u00E5", "aelig" to "\u00E6", "ccedil" to "\u00E7", "egrave" to "\u00E8", "eacute" to "\u00E9",
    "ecirc" to "\u00EA", "euml" to "\u00EB", "igrave" to "\u00EC", "iacute" to "\u00ED", "icirc" to "\u00EE",
    "iuml" to "\u00EF", "ntilde" to "\u00F1", "ograve" to "\u00F2", "oacute" to "\u00F3", "ocirc" to "\u00F4",
    "otilde" to "\u00F5", "ouml" to "\u00F6", "oslash" to "\u00F8", "ugrave" to "\u00F9", "uacute" to "\u00FA",
    "ucirc" to "\u00FB", "uuml" to "\u00FC", "yacute" to "\u00FD", "yuml" to "\u00FF", "OElig" to "\u0152",
    "oelig" to "\u0153", "Scaron" to "\u0160", "scaron" to "\u0161",
)
