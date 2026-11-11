package app.diurna.data.fdc

import app.diurna.domain.AminoAcids
import app.diurna.domain.MicronutrientProfile
import app.diurna.domain.NutrientId
import app.diurna.domain.NutrientRegistry
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
        // The Charset overload is API 33+; minSdk is 29, where it throws NoSuchMethodError.
        val q = URLEncoder.encode(query.trim(), "UTF-8")
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


    /**
     * Raw food JSON for [fdcId] (includes foodPortions when present). Null without a key /
     * network fail. Prefer Foundation / SR Legacy ids from [search].
     */
    suspend fun foodJson(fdcId: Int): JSONObject? = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val body = transport.get("/fdc/v1/food/$fdcId", key) ?: return@withContext null
        runCatching { JSONObject(body) }.getOrNull()
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

    /**
     * The USDA Branded Foods record whose GTIN/UPC is [code] (a normalized EAN-13 or EAN-8), or
     * null. Asks for the 12-digit UPC-A spelling first (how most US records store it), then the
     * 13-digit one; only an exact code match counts, so a full-text hit can't stand in for it.
     */
    suspend fun brandedByGtin(code: String): FdcBrandedFood? = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val want = normalizeGtin(code) ?: return@withContext null
        val spellings = if (want.length == 13 && want[0] == '0') listOf(want.substring(1), want) else listOf(want)
        for (q in spellings) {
            val match = brandedFoods(q, 5, key).firstOrNull { it.gtin == want }
            if (match != null) return@withContext match
        }
        null
    }

    /** Branded Foods matching [query] (product name, brand), best first. Empty without a key. */
    suspend fun brandedSearch(query: String, pageSize: Int = 5): List<FdcBrandedFood> = withContext(Dispatchers.IO) {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val q = query.trim().take(MAX_QUERY)
        if (q.isEmpty()) return@withContext emptyList()
        brandedFoods(q, pageSize.coerceIn(1, 10), key)
    }

    private fun brandedFoods(query: String, size: Int, key: String): List<FdcBrandedFood> {
        val q = URLEncoder.encode(query, "UTF-8") // the Charset overload is API 33+
        val body = transport.get("/fdc/v1/foods/search?query=$q&dataType=Branded&pageSize=$size&pageNumber=1", key)
            ?: return emptyList()
        val foods = runCatching { JSONObject(body).optJSONArray("foods") }.getOrNull() ?: return emptyList()
        return (0 until foods.length()).mapNotNull { i -> foods.optJSONObject(i)?.let(::parseBranded) }
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

        // Micronutrients (canonical units). IU / non-DFE folate ids are handled in parseMicronutrients.
        const val N_SODIUM = 1093
        const val N_POTASSIUM = 1092
        const val N_CALCIUM = 1087
        const val N_IRON = 1089
        const val N_MAGNESIUM = 1090
        const val N_ZINC = 1095
        const val N_VITAMIN_A_RAE = 1106
        const val N_VITAMIN_A_IU = 1104
        const val N_VITAMIN_C = 1162
        const val N_VITAMIN_D_UG = 1114
        const val N_VITAMIN_D_IU = 1110
        const val N_VITAMIN_B12 = 1178
        const val N_FOLATE_DFE = 1177

        private const val MAX_QUERY = 120
        /** Above this a per-100 g energy is a typo (pure fat is ~900 kcal/100 g). */
        private const val MAX_KCAL_PER_100G = 950.0

        /** GTIN-14/13/12/8 digits as the app's normalized barcode (EAN-13 or EAN-8), else null. */
        fun normalizeGtin(raw: String): String? {
            val digits = raw.trim()
            if (digits.isEmpty() || !digits.all { it in '0'..'9' } || digits.length > 14) return null
            var d = digits
            while (d.length > 13 && d[0] == '0') d = d.substring(1)
            return app.diurna.data.normalizeBarcode(d)
        }

        /**
         * One Branded Foods record (search hit or detail) as plain per-100 g values. Keys match
         * the OFF snapshot's per100 keys. Missing nutrients are absent, never 0. Null without a
         * usable, plausible energy value.
         */
        fun parseBranded(food: JSONObject): FdcBrandedFood? {
            val nutrients = food.optJSONArray("foodNutrients") ?: return null
            val byId = mutableMapOf<Int, Double>()
            val normalized = org.json.JSONArray()
            for (i in 0 until nutrients.length()) {
                val n = nutrients.optJSONObject(i) ?: continue
                val id = nutrientId(n) ?: continue
                // Search hits say "value"; food detail says "amount".
                val v = n.optDouble("amount", n.optDouble("value", Double.NaN))
                if (!v.isFinite() || v < 0) continue
                byId[id] = v
                normalized.put(JSONObject().put("nutrientId", id).put("amount", v))
            }
            val kcal = byId[N_ENERGY_KCAL]?.takeIf { it <= MAX_KCAL_PER_100G } ?: return null
            val per100 = linkedMapOf("kcal" to kcal)
            fun put(key: String, id: Int, max: Double = 100.0) { byId[id]?.takeIf { it <= max }?.let { per100[key] = it } }
            put("protein_g", N_PROTEIN); put("carbs_g", N_CARBS); put("fat_g", N_FAT)
            put("fiber_g", N_FIBER); put("sugars_g", N_SUGARS); put("added_sugars_g", N_ADDED_SUGARS)
            put("starch_g", N_STARCH); put("sat_fat_g", N_SAT); put("mono_fat_g", N_MONO)
            put("poly_fat_g", N_POLY); put("trans_fat_g", N_TRANS); put("cholesterol_mg", N_CHOL, max = 5_000.0)
            fun text(name: String, max: Int): String? =
                food.optString(name).takeIf { food.has(name) && !food.isNull(name) }
                    ?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")
                    ?.replace(Regex("\\s+"), " ")?.trim()?.take(max)?.ifEmpty { null }
            val unit = food.optString("servingSizeUnit").trim().lowercase()
            val size = food.optDouble("servingSize", Double.NaN)
            val servingGrams = size.takeIf { it.isFinite() && it > 0 && it <= 1_000 && (unit == "g" || unit == "grm") }
            return FdcBrandedFood(
                fdcId = food.optInt("fdcId", 0),
                description = text("description", 120).orEmpty(),
                brand = text("brandName", 80) ?: text("brandOwner", 80),
                gtin = text("gtinUpc", 20)?.let(::normalizeGtin),
                ingredients = text("ingredients", 2_000),
                servingGrams = servingGrams,
                servingText = text("householdServingFullText", 60),
                per100 = per100,
                micros = parseMicronutrients(JSONObject().put("foodNutrients", normalized)),
            )
        }

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


        /**
         * Per-100g micronutrients from a food detail/search JSON. Null when none of the first-wave
         * nutrients are present with a usable amount. Missing ≠ zero; vitamin A IU alone never
         * becomes RAE; vitamin D IU converts with [NutrientRegistry.VITAMIN_D_IU_TO_UG] only when
         * µg is absent; folate without DFE is ignored.
         */
        fun parseMicronutrients(food: JSONObject): MicronutrientProfile? {
            val nutrients = food.optJSONArray("foodNutrients") ?: return null
            val byId = mutableMapOf<Int, Double>()
            for (i in 0 until nutrients.length()) {
                val n = nutrients.getJSONObject(i)
                val id = nutrientId(n) ?: continue
                val amount = n.optDouble("amount", Double.NaN)
                val sanitized = NutrientRegistry.sanitize(amount) ?: continue
                byId[id] = sanitized
            }
            if (byId.isEmpty()) return null
            val pairs = ArrayList<Pair<NutrientId, Double>>()
            fun take(id: NutrientId, fdcId: Int) {
                val v = byId[fdcId] ?: return
                pairs += id to v
            }
            take(NutrientId.SODIUM, N_SODIUM)
            take(NutrientId.POTASSIUM, N_POTASSIUM)
            take(NutrientId.CALCIUM, N_CALCIUM)
            take(NutrientId.IRON, N_IRON)
            take(NutrientId.MAGNESIUM, N_MAGNESIUM)
            take(NutrientId.ZINC, N_ZINC)
            take(NutrientId.VITAMIN_A_RAE, N_VITAMIN_A_RAE) // never N_VITAMIN_A_IU
            take(NutrientId.VITAMIN_C, N_VITAMIN_C)
            when {
                byId[N_VITAMIN_D_UG] != null -> pairs += NutrientId.VITAMIN_D to byId.getValue(N_VITAMIN_D_UG)
                byId[N_VITAMIN_D_IU] != null -> {
                    val ug = byId.getValue(N_VITAMIN_D_IU) * NutrientRegistry.VITAMIN_D_IU_TO_UG
                    NutrientRegistry.sanitize(ug)?.let { pairs += NutrientId.VITAMIN_D to it }
                }
            }
            take(NutrientId.VITAMIN_B12, N_VITAMIN_B12)
            take(NutrientId.FOLATE_DFE, N_FOLATE_DFE)
            val profile = MicronutrientProfile.fromPairs(pairs)
            return profile.takeIf { it.hasAny }
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

/**
 * A USDA Branded Foods record. [per100] uses the OFF snapshot keys (kcal, protein_g, …,
 * cholesterol_mg) per 100 g; absent keys are unknown. [servingGrams] is null for volume servings.
 */
data class FdcBrandedFood(
    val fdcId: Int,
    val description: String,
    val brand: String?,
    val gtin: String?,
    val ingredients: String?,
    val servingGrams: Double?,
    val servingText: String?,
    val per100: Map<String, Double>,
    val micros: MicronutrientProfile?,
)

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
            if (code !in 200..299) return null
            conn.inputStream.use { readCapped(it) }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** A hostile or broken server can't make the app buffer an unbounded answer. */
    private fun readCapped(input: java.io.InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }

    companion object {
        /** Food detail with portions and branded search pages are well under this. */
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
