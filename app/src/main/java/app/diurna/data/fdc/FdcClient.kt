package app.diurna.data.fdc

import app.diurna.domain.AminoAcids
import app.diurna.domain.RecipeMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * USDA FoodData Central client. HTTPS only to api.nal.usda.gov. Auth via X-Api-Key header
 * (also accepted as api_key query param; header preferred so the key stays out of URLs/logs).
 *
 * Amino profiles live mainly on Foundation and SR Legacy foods; Branded rarely has them.
 * Values from FDC are g/100g (aminos) or nutrient-specific units; we normalize to per-100g
 * for [AminoProfile] / [FdcNutritionProfile].
 */
class FdcClient(
    private val apiKey: () -> String?,
    private val transport: FdcTransport = DefaultFdcTransport(),
) {
    fun hasApiKey(): Boolean = !apiKey().isNullOrBlank()

    /** Search Foundation + SR Legacy; return the top hit's fdcId and description, or null. */
    suspend fun searchTop(query: String): FdcSearchHit? =
        search(query, pageSize = 1).firstOrNull()

    /** Search Foundation + SR Legacy; up to [pageSize] hits (default 10). Empty without a key. */
    suspend fun search(query: String, pageSize: Int = 10): List<FdcSearchHit> = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8)
        val size = pageSize.coerceIn(1, 25)
        val path = "/fdc/v1/foods/search?query=$q" +
            "&dataType=Foundation,SR%20Legacy&pageSize=$size&pageNumber=1"
        val body = transport.get(path, key) ?: return@withContext emptyList()
        val foods = JSONObject(body).optJSONArray("foods") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until foods.length()) {
                val f = foods.getJSONObject(i)
                add(FdcSearchHit(f.getInt("fdcId"), f.optString("description", query)))
            }
        }
    }

    /** Per-100g amino profile for [fdcId], or null if missing / no key / network fail. */
    suspend fun aminoProfile(fdcId: Int): AminoProfile? = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val body = transport.get("/fdc/v1/food/$fdcId", key) ?: return@withContext null
        parseAminoProfile(JSONObject(body))
    }

    /**
     * Full per-100g nutrition for recipe lines: macros, optional sub-macros, and aminos.
     * Null without a key / network fail / empty food.
     */
    suspend fun nutritionProfile(fdcId: Int): FdcNutritionProfile? = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val body = transport.get("/fdc/v1/food/$fdcId", key) ?: return@withContext null
        parseNutritionProfile(JSONObject(body))
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

        /** Macro / sub-macro FDC nutrient ids (amounts typically g/100g; energy kcal/100g; chol mg/100g). */
        const val N_ENERGY_KCAL = 1008
        const val N_PROTEIN = 1003
        const val N_CARBS = 1005
        const val N_FAT = 1004
        const val N_FIBER = 1079
        const val N_SUGARS = 2000
        const val N_ADDED_SUGARS = 1235
        const val N_STARCH = 1009
        const val N_SAT = 1258
        const val N_MONO = 1292
        const val N_POLY = 1293
        const val N_TRANS = 1257
        const val N_CHOL = 1253

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

        fun parseNutritionProfile(food: JSONObject): FdcNutritionProfile? {
            val nutrients = food.optJSONArray("foodNutrients") ?: return null
            val byId = mutableMapOf<Int, Double>()
            for (i in 0 until nutrients.length()) {
                val n = nutrients.getJSONObject(i)
                val id = nutrientId(n) ?: continue
                val amount = n.optDouble("amount", Double.NaN)
                if (amount.isNaN()) continue
                byId[id] = amount
            }
            if (byId.isEmpty()) return null
            fun g(id: Int): Int? = byId[id]?.roundToInt()?.coerceAtLeast(0)
            fun mgChol(id: Int): Int? = byId[id]?.roundToInt()?.coerceAtLeast(0)
            val aminos = parseAminoProfile(food)?.aminos ?: AminoAcids()
            val protein = g(N_PROTEIN) ?: 0
            val carbs = g(N_CARBS) ?: 0
            val fat = g(N_FAT) ?: 0
            val kcal = g(N_ENERGY_KCAL)
                ?: app.diurna.domain.MacroEnergy.kcal(protein, carbs, fat, g(N_FIBER)).total.roundToInt()
            return FdcNutritionProfile(
                fdcId = food.optInt("fdcId", 0),
                description = food.optString("description", ""),
                per100 = RecipeMath.IngredientNutrition(
                    proteinG = protein,
                    carbsG = carbs,
                    fatG = fat,
                    fiberG = g(N_FIBER),
                    sugarsG = g(N_SUGARS),
                    addedSugarsG = g(N_ADDED_SUGARS),
                    starchG = g(N_STARCH),
                    satFatG = g(N_SAT),
                    monoFatG = g(N_MONO),
                    polyFatG = g(N_POLY),
                    transFatG = g(N_TRANS),
                    cholesterolMg = mgChol(N_CHOL),
                    aminos = aminos,
                ),
                kcalPer100 = kcal.coerceAtLeast(0),
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

/** Per-100g macros + optional detail + aminos for recipe ingredient lines. */
data class FdcNutritionProfile(
    val fdcId: Int,
    val description: String,
    val per100: RecipeMath.IngredientNutrition,
    val kcalPer100: Int,
) {
    fun forGrams(grams: Double): RecipeMath.IngredientNutrition =
        RecipeMath.scalePer100(per100, grams)
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
