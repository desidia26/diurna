package app.diurna.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * One form per product, so a can scanned as UPC-A (12 digits) matches the same can saved as
 * EAN-13 (the same digits with a leading 0). UPC-E, the short code on small packages, is expanded
 * to its UPC-A first. Null for anything that isn't a product barcode.
 */
fun normalizeBarcode(raw: String, upcE: Boolean = false): String? {
    var digits = raw.trim()
    if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
    if (upcE) digits = expandUpcE(digits) ?: return null
    return when (digits.length) {
        12 -> "0$digits"
        8, 13 -> digits
        else -> null
    }
}

/** UPC-E (8 digits: number system, 6 digits, check) to its 12-digit UPC-A. */
fun expandUpcE(code: String): String? {
    if (code.length != 8) return null
    val ns = code[0]
    val d = code.substring(1, 7)
    val check = code[7]
    val body = when (d[5]) {
        '0', '1', '2' -> d.substring(0, 2) + d[5] + "0000" + d.substring(2, 5)
        '3' -> d.substring(0, 3) + "00000" + d.substring(3, 5)
        '4' -> d.substring(0, 4) + "00000" + d[4]
        else -> d.substring(0, 5) + "0000" + d[5]
    }
    return "$ns$body$check"
}

/** Where product data comes from; a fake in tests. */
fun interface ProductSource {
    /** The product's JSON, or null if the barcode isn't known. Throws when it can't be reached. */
    suspend fun fetch(code: String): String?
}

/**
 * openfoodfacts.org: free, no key, crowd-sourced.
 *
 * Its answers are treated as untrusted: anyone can edit a product there. The request is HTTPS only
 * (Android refuses cleartext and user-installed certificates by default), never follows redirects,
 * gives up after [DEADLINE_MS] in total, and reads at most [MAX_BYTES]. What comes back is only
 * ever parsed into numbers and short plain text by [parseOffProduct], and shown for a check
 * before anything is saved.
 */
class OpenFoodFacts : ProductSource {
    override suspend fun fetch(code: String): String? {
        // Digits only by construction; checked again since it goes into a URL.
        require(code.length in 8..13 && code.all { it in '0'..'9' }) { "not a barcode" }
        return withContext(Dispatchers.IO) {
            val url = URL("https://world.openfoodfacts.org/api/v2/product/$code?fields=product_name,brands,serving_size,serving_quantity,nutriments")
            val conn = url.openConnection() as HttpURLConnection
            coroutineScope {
                // Per-read timeouts alone don't bound a server that drips bytes slowly.
                val deadline = launch { delay(DEADLINE_MS); conn.disconnect() }
                try {
                    conn.instanceFollowRedirects = false
                    // Open Food Facts asks every app to identify itself. Nothing about the user is sent.
                    conn.setRequestProperty("User-Agent", "Diurna/1.0 (https://github.com/desidia26/diurna)")
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    when (conn.responseCode) {
                        HttpURLConnection.HTTP_OK -> conn.inputStream.use { readCapped(it) }
                        HttpURLConnection.HTTP_NOT_FOUND -> null
                        else -> throw IOException("HTTP ${conn.responseCode}")
                    }
                } catch (e: FileNotFoundException) {
                    null
                } finally {
                    deadline.cancel()
                    conn.disconnect()
                }
            }
        }
    }

    private fun readCapped(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw IOException("response too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val DEADLINE_MS = 15_000L
        /** A product answer with only the fields asked for is a few KB. */
        const val MAX_BYTES = 256 * 1024
    }
}

// Sanity limits for numbers from Open Food Facts; beyond them a value is a typo or vandalism.
private const val MAX_KCAL = 5_000.0 // per serving
private const val MAX_GRAMS = 1_000.0 // per serving, for macros and the serving weight
private const val MAX_NAME = 80
private const val MAX_NOTE = 60

/**
 * Single-line printable text, length-capped. Control characters (newlines, tabs, NUL) become
 * spaces; invisible formatting characters (e.g. right-to-left overrides that flip how text
 * displays) are dropped.
 */
private fun cleanText(s: String, max: Int): String =
    s.map { if (it.isISOControl()) ' ' else it }.filter { Character.getType(it) != Character.FORMAT.toInt() }.joinToString("")
        .replace(Regex("\\s+"), " ").trim().take(max).trim()

/**
 * An Open Food Facts response as an unsaved meal, or null when it has no product or no calories.
 * Numbers are per serving when the label gives one, else per 100 g scaled to the serving size,
 * else per 100 g.
 */
fun parseOffProduct(json: String): MealEntity? = try {
    parseOrThrow(json)
} catch (e: JSONException) {
    null
}

private fun parseOrThrow(json: String): MealEntity? {
    val root = JSONObject(json)
    if (root.optInt("status", 1) == 0) return null
    val p = root.optJSONObject("product") ?: return null
    val n = p.optJSONObject("nutriments") ?: return null
    /** A finite, non-negative number, or null. Rejects NaN, Infinity and negatives. */
    fun num(key: String): Double? = n.opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    fun kcal(suffix: String): Double? = num("energy-kcal$suffix") ?: num("energy$suffix")?.let { it / 4.184 }

    val serving = cleanText(p.optString("serving_size"), MAX_NOTE).ifEmpty { null }
    val grams = p.opt("serving_quantity")?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= MAX_GRAMS }
    val (scale, suffix, note) = when {
        kcal("_serving") != null -> Triple(1.0, "_serving", serving)
        grams != null && kcal("_100g") != null -> Triple(grams / 100, "_100g", serving ?: "${grams.roundToInt()} g")
        kcal("_100g") != null -> Triple(1.0, "_100g", "100 g")
        else -> return null
    }
    val kcal = kcal(suffix)!! * scale
    if (kcal > MAX_KCAL) return null
    fun amount(x: Double?) = ((x ?: 0.0) * scale).coerceAtMost(MAX_GRAMS).roundToInt()

    val name = cleanText(p.optString("product_name"), MAX_NAME)
    val brand = cleanText(p.optString("brands").split(",").first(), MAX_NAME)
    return MealEntity(
        name = when {
            name.isEmpty() && brand.isEmpty() -> "Scanned item"
            brand.isEmpty() || name.contains(brand, ignoreCase = true) -> name.ifEmpty { brand }
            else -> "$name ($brand)".take(MAX_NAME)
        },
        kcal = kcal.roundToInt(),
        proteinG = amount(num("proteins$suffix")),
        carbsG = amount(num("carbohydrates$suffix")),
        fatG = amount(num("fat$suffix")),
        note = note,
    )
}
