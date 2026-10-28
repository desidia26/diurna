package app.diurna.data.fdc

import app.diurna.domain.AminoAcids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * USDA FoodData Central client. HTTPS only to api.nal.usda.gov. Auth via X-Api-Key header
 * (also accepted as api_key query param; header preferred so the key stays out of URLs/logs).
 *
 * Amino profiles live mainly on Foundation and SR Legacy foods; Branded rarely has them.
 * Values from FDC are g/100g; we convert to mg/100g for [AminoProfile].
 */
class FdcClient(
    private val apiKey: () -> String?,
    private val transport: FdcTransport = DefaultFdcTransport(),
) {
    /** Search Foundation + SR Legacy; return the top hit's fdcId and description, or null. */
    suspend fun searchTop(query: String): FdcSearchHit? = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8)
        val path = "/fdc/v1/foods/search?query=$q" +
            "&dataType=Foundation,SR%20Legacy&pageSize=1&pageNumber=1"
        val body = transport.get(path, key) ?: return@withContext null
        val foods = JSONObject(body).optJSONArray("foods") ?: return@withContext null
        if (foods.length() == 0) return@withContext null
        val f = foods.getJSONObject(0)
        FdcSearchHit(f.getInt("fdcId"), f.optString("description", query))
    }

    /** Per-100g amino profile for [fdcId], or null if missing / no key / network fail. */
    suspend fun aminoProfile(fdcId: Int): AminoProfile? = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val body = transport.get("/fdc/v1/food/$fdcId", key) ?: return@withContext null
        parseAminoProfile(JSONObject(body))
    }

    companion object {
        const val HOST = "api.nal.usda.gov"
        /** Public demo key — heavily rate-limited; fine for manual probes, never for production. */
        const val DEMO_KEY = "DEMO_KEY"

        /**
         * FDC nutrient id → amino field. Amounts in foodNutrients are g per 100 g;
         * stored as mg per 100 g (×1000). Cystine (1216) maps to cysteine.
         */
        val NUTRIENT_TO_FIELD: Map<Int, String> = mapOf(
            1221 to "his",
            1212 to "ile",
            1213 to "leu",
            1214 to "lys",
            1215 to "met",
            1217 to "phe",
            1211 to "thr",
            1210 to "trp",
            1219 to "val",
            1216 to "cys",
            1218 to "tyr",
        )

        fun parseAminoProfile(food: JSONObject): AminoProfile? {
            val nutrients = food.optJSONArray("foodNutrients") ?: return null
            var his: Int? = null; var ile: Int? = null; var leu: Int? = null
            var lys: Int? = null; var met: Int? = null; var phe: Int? = null
            var thr: Int? = null; var trp: Int? = null; var val_: Int? = null
            var cys: Int? = null; var tyr: Int? = null
            var any = false
            for (i in 0 until nutrients.length()) {
                val n = nutrients.getJSONObject(i)
                val id = nutrientId(n) ?: continue
                if (id !in NUTRIENT_TO_FIELD) continue
                val amountG = n.optDouble("amount", Double.NaN)
                if (amountG.isNaN()) continue
                val mgPer100 = (amountG * 1000.0).toInt().coerceAtLeast(0)
                any = true
                when (id) {
                    1221 -> his = mgPer100
                    1212 -> ile = mgPer100
                    1213 -> leu = mgPer100
                    1214 -> lys = mgPer100
                    1215 -> met = mgPer100
                    1217 -> phe = mgPer100
                    1211 -> thr = mgPer100
                    1210 -> trp = mgPer100
                    1219 -> val_ = mgPer100
                    1216 -> cys = mgPer100
                    1218 -> tyr = mgPer100
                }
            }
            if (!any) return null
            return AminoProfile(
                fdcId = food.optInt("fdcId", 0),
                description = food.optString("description", ""),
                aminos = AminoAcids(his, ile, leu, lys, met, phe, thr, trp, val_, cys, tyr),
            )
        }

        private fun nutrientId(n: JSONObject): Int? {
            // Foundation/SR Legacy detail: nested nutrient.id; search hits may use nutrientId
            n.optJSONObject("nutrient")?.let { nested ->
                nested.optInt("id").takeIf { it > 0 }?.let { return it }
            }
            n.optInt("nutrientId").takeIf { it > 0 }?.let { return it }
            return null
        }
    }
}

data class FdcSearchHit(val fdcId: Int, val description: String)

/** Per-100g amino milligrams from one FDC food. */
data class AminoProfile(
    val fdcId: Int,
    val description: String,
    val aminos: AminoAcids,
) {
    /** Scale per-100g profile by [grams] of food. */
    fun forGrams(grams: Double): AminoAcids = aminos.scale(grams / 100.0)
}

fun interface FdcTransport {
    /** GET path (starting with /fdc/...) with X-Api-Key; return body or null on failure. */
    fun get(path: String, apiKey: String): String?
}

class DefaultFdcTransport(
    private val baseUrl: String = "https://${FdcClient.HOST}",
) : FdcTransport {
    override fun get(path: String, apiKey: String): String? {
        require(baseUrl.startsWith("https://")) { "FDC must use HTTPS" }
        val url = URL(baseUrl + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("X-Api-Key", apiKey)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            if (code in 200..299) body else null
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
