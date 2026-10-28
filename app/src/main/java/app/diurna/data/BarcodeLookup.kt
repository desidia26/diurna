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
private const val MAX_CHOL_MG = 5_000.0 // per serving; OFF stores cholesterol in grams
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
    /** Required macros: missing counts as 0 (meal columns are non-null). */
    fun amount(x: Double?) = ((x ?: 0.0) * scale).coerceAtMost(MAX_GRAMS).roundToInt()
    /** Optional detail: missing stays null, distinct from a real 0. */
    fun optGrams(vararg keys: String): Int? {
        val v = keys.firstNotNullOfOrNull { num("$it$suffix") } ?: return null
        return (v * scale).coerceAtMost(MAX_GRAMS).roundToInt()
    }
    fun optCholMg(): Int? {
        val g = num("cholesterol$suffix") ?: return null
        return (g * 1000.0 * scale).coerceAtMost(MAX_CHOL_MG).roundToInt()
    }

    // OFF carbohydrates exclude fiber (available carbs). Store US-style total carbs when fiber is known.
    val availCarbs = num("carbohydrates$suffix")
    val fiberRaw = num("fiber$suffix")
    val carbsG = when {
        availCarbs != null && fiberRaw != null -> amount(availCarbs + fiberRaw)
        availCarbs != null -> amount(availCarbs)
        else -> 0
    }

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
        carbsG = carbsG,
        fatG = amount(num("fat$suffix")),
        note = note,
        fiberG = fiberRaw?.let { amount(it) },
        solubleFiberG = optGrams("soluble-fiber", "fiber-soluble"),
        insolubleFiberG = optGrams("insoluble-fiber", "fiber-insoluble"),
        sugarsG = optGrams("sugars"),
        addedSugarsG = optGrams("added-sugars"),
        starchG = optGrams("starch"),
        satFatG = optGrams("saturated-fat"),
        monoFatG = optGrams("monounsaturated-fat"),
        polyFatG = optGrams("polyunsaturated-fat"),
        transFatG = optGrams("trans-fat"),
        cholesterolMg = optCholMg(),
        omega3G = optGrams("omega-3-fat"),
        omega6G = optGrams("omega-6-fat"),
        omega9G = optGrams("omega-9-fat"),
    )
}

/**
 * Source snapshot from an Open Food Facts lookup, stored on the meal and/or food entry.
 *
 * OFF product data is public/crowd-sourced (no personal data). [rawJson] is capped so a hostile
 * response cannot bloat the DB; [encode] drops it when over [MAX_RAW_CHARS].
 * Logged macros on the entry are never silently overwritten from this snapshot — backfill and
 * re-estimate only fill nulls (or replace on an explicit re-estimate).
 */
data class OffSnapshot(
    val productName: String? = null,
    val servingSize: String? = null,
    /**
     * Nutrients for [basisGrams] of product (usually 100 g from OFF `_100g` keys).
     * When OFF only had per-serving numbers and no quantity, those values are stored here with
     * [basisGrams] = 100 so [toMacros]/[basisGrams]) yields one serving.
     */
    val per100Json: String? = null,
    /** Grams the [per100Json] values correspond to (default 100). */
    val basisGrams: Double = 100.0,
    val lookedUpAt: Long,
    val lookupOk: Boolean,
    /** Capped raw OFF response; null when omitted or too large. */
    val rawJson: String? = null,
) {
    fun encode(): String {
        val o = JSONObject()
        o.put("lookedUpAt", lookedUpAt)
        o.put("lookupOk", lookupOk)
        o.put("basisG", basisGrams)
        if (productName != null) o.put("productName", productName)
        if (servingSize != null) o.put("servingSize", servingSize)
        if (per100Json != null) o.put("per100", JSONObject(per100Json))
        val raw = rawJson?.takeIf { it.length <= MAX_RAW_CHARS }
        if (raw != null) o.put("raw", raw)
        return o.toString()
    }

    companion object {
        /** Keep raw OFF payloads small; product JSON with selected fields is typically a few KB. */
        const val MAX_RAW_CHARS = 32 * 1024

        fun decode(json: String?): OffSnapshot? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                OffSnapshot(
                    productName = o.optString("productName").ifEmpty { null },
                    servingSize = o.optString("servingSize").ifEmpty { null },
                    per100Json = o.optJSONObject("per100")?.toString(),
                    basisGrams = o.optDouble("basisG", 100.0).takeIf { it.isFinite() && it > 0 } ?: 100.0,
                    lookedUpAt = o.getLong("lookedUpAt"),
                    lookupOk = o.optBoolean("lookupOk", false),
                    rawJson = o.optString("raw").ifEmpty { null },
                )
            } catch (_: JSONException) {
                null
            }
        }

        fun failed(at: Long = System.currentTimeMillis()) =
            OffSnapshot(lookedUpAt = at, lookupOk = false)
    }
}

/**
 * Parses nutrients and product name/serving from an OFF response into an [OffSnapshot].
 * Prefers true per-100g; otherwise converts per-serving using `serving_quantity`, or stores
 * per-serving values with [OffSnapshot.basisGrams] = 100 (one serving ≡ that basis).
 * Null when there is no usable energy.
 */
fun parseOffSnapshot(json: String, lookedUpAt: Long = System.currentTimeMillis()): OffSnapshot? {
    return try {
        val root = JSONObject(json)
        if (root.optInt("status", 1) == 0) return null
        val p = root.optJSONObject("product") ?: return null
        val n = p.optJSONObject("nutriments") ?: return null
        fun num(key: String): Double? =
            n.opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        fun kcal(suffix: String): Double? =
            num("energy-kcal$suffix") ?: num("energy$suffix")?.let { it / 4.184 }
        val qty = p.opt("serving_quantity")?.toString()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0 && it <= MAX_GRAMS }
        // Scale factors to store nutrients for [basisG] grams in per100Json.
        val (suffix, toBasis, basisG) = when {
            kcal("_100g") != null -> Triple("_100g", 1.0, 100.0)
            qty != null && kcal("_serving") != null -> Triple("_serving", 100.0 / qty, 100.0)
            kcal("_serving") != null -> Triple("_serving", 1.0, 100.0) // one serving as basis
            else -> return null
        }
        val kcalVal = kcal(suffix)!! * toBasis
        if (kcalVal > MAX_KCAL) return null
        val per100 = JSONObject()
        per100.put("kcal", kcalVal.coerceAtMost(MAX_KCAL).roundToInt())
        fun putG(outKey: String, vararg keys: String) {
            val v = keys.firstNotNullOfOrNull { num("$it$suffix") } ?: return
            per100.put(outKey, (v * toBasis).coerceAtMost(MAX_GRAMS))
        }
        putG("protein_g", "proteins")
        val avail = num("carbohydrates$suffix")
        val fiber = num("fiber$suffix")
        when {
            avail != null && fiber != null ->
                per100.put("carbs_g", ((avail + fiber) * toBasis).coerceAtMost(MAX_GRAMS))
            avail != null -> per100.put("carbs_g", (avail * toBasis).coerceAtMost(MAX_GRAMS))
        }
        putG("fat_g", "fat")
        putG("fiber_g", "fiber")
        putG("sugars_g", "sugars")
        putG("added_sugars_g", "added-sugars")
        putG("starch_g", "starch")
        putG("sat_fat_g", "saturated-fat")
        putG("mono_fat_g", "monounsaturated-fat")
        putG("poly_fat_g", "polyunsaturated-fat")
        putG("trans_fat_g", "trans-fat")
        num("cholesterol$suffix")?.let {
            per100.put("cholesterol_mg", (it * 1000.0 * toBasis).coerceAtMost(MAX_CHOL_MG))
        }
        putG("omega3_g", "omega-3-fat")
        putG("omega6_g", "omega-6-fat")
        putG("omega9_g", "omega-9-fat")
        putG("soluble_fiber_g", "soluble-fiber", "fiber-soluble")
        putG("insoluble_fiber_g", "insoluble-fiber", "fiber-insoluble")

        val name = cleanText(p.optString("product_name"), MAX_NAME).ifEmpty { null }
        val serving = cleanText(p.optString("serving_size"), MAX_NOTE).ifEmpty { null }
        val raw = if (json.length <= OffSnapshot.MAX_RAW_CHARS) json else null
        OffSnapshot(
            productName = name,
            servingSize = serving,
            per100Json = per100.toString(),
            basisGrams = basisG,
            lookedUpAt = lookedUpAt,
            lookupOk = true,
            rawJson = raw,
        )
    } catch (_: JSONException) {
        null
    }
}

/** Macros (kcal / P / C / F) from per-100g snapshot scaled by grams; null if snapshot unusable. */
fun OffSnapshot.toMacros(grams: Double): OffMacros? {
    if (!lookupOk || per100Json == null || grams <= 0 || basisGrams <= 0) return null
    val n = try { JSONObject(per100Json) } catch (_: JSONException) { return null }
    val scale = grams / basisGrams
    fun req(key: String): Int {
        val v = n.optDouble(key, 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
        return (v * scale).coerceAtMost(if (key == "kcal") MAX_KCAL else MAX_GRAMS).roundToInt()
    }
    if (!n.has("kcal")) return null
    return OffMacros(req("kcal"), req("protein_g"), req("carbs_g"), req("fat_g"))
}

data class OffMacros(val kcal: Int, val proteinG: Int, val carbsG: Int, val fatG: Int)

/** Grams for one serving from the snapshot; defaults to [OffSnapshot.basisGrams]. */
fun OffSnapshot.servingGrams(): Double {
    rawJson?.let { raw ->
        try {
            val p = JSONObject(raw).optJSONObject("product")
            val q = p?.opt("serving_quantity")?.toString()?.toDoubleOrNull()
            if (q != null && q.isFinite() && q > 0 && q <= MAX_GRAMS) return q
        } catch (_: JSONException) { }
    }
    servingSize?.let { s ->
        val m = Regex("""([\d]+(?:\.\d+)?)\s*g\b""", RegexOption.IGNORE_CASE).find(s)
        val g = m?.groupValues?.get(1)?.toDoubleOrNull()
        if (g != null && g.isFinite() && g > 0 && g <= MAX_GRAMS) return g
    }
    return basisGrams
}
