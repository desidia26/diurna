package app.diurna.data

import app.diurna.domain.FoodIngredient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Converts Open Food Facts structured ingredients into the {name, grams} list used by
 * [app.diurna.data.fdc.AminoResolver]. Top-level only (nested sub-ingredients are skipped);
 * grams = (percent or percent_estimate) / 100 × serving grams. When percentages sum above 100
 * (an OFF quirk), they are scaled down so grams sum ≈ serving.
 */
object OffIngredients {
    const val SOURCE_OFF = "off"
    const val SOURCE_AI = "ai"

    data class Conversion(
        val ingredients: List<FoodIngredient>,
        /** True when at least one top-level ingredient had a usable percent. */
        val usable: Boolean,
    )

    /**
     * Parse the OFF `ingredients` array (as stored on [OffSnapshot.ingredientsJson]) into
     * per-serving grams. Returns [Conversion.usable]=false when nothing can be converted
     * (missing percents, empty list, or no serving weight) — caller should fall back to AI.
     */
    fun fromStructured(ingredientsJson: String?, servingGrams: Double): Conversion {
        if (ingredientsJson.isNullOrBlank() || servingGrams <= 0 || !servingGrams.isFinite()) {
            return Conversion(emptyList(), usable = false)
        }
        val arr = try {
            JSONArray(ingredientsJson)
        } catch (_: JSONException) {
            return Conversion(emptyList(), usable = false)
        }
        data class Row(val name: String, val pct: Double)
        val rows = ArrayList<Row>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pct = percentOf(o) ?: continue
            if (pct <= 0.0) continue
            val name = normalizeName(o) ?: continue
            rows += Row(name, pct)
        }
        if (rows.isEmpty()) return Conversion(emptyList(), usable = false)
        val sum = rows.sumOf { it.pct }
        val scale = if (sum > 100.0) 100.0 / sum else 1.0
        val out = rows.mapNotNull { row ->
            val grams = servingGrams * (row.pct * scale) / 100.0
            if (grams <= 0.0 || !grams.isFinite()) null
            else FoodIngredient(name = row.name, grams = (grams * 10).roundToInt() / 10.0)
        }
        return Conversion(out, usable = out.isNotEmpty())
    }

    /** Prefer a real labeled percent; fall back to OFF's estimate. */
    fun percentOf(o: JSONObject): Double? {
        fun read(key: String): Double? =
            o.opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        return read("percent") ?: read("percent_estimate")
    }

    /**
     * FDC-friendly generic name from an OFF ingredient object.
     * Prefers human `text`, else taxonomy `id` (`en:palm-oil` → `palm oil`).
     */
    fun normalizeName(o: JSONObject): String? {
        val text = o.optString("text").trim()
        if (text.isNotEmpty()) return cleanIngredientName(text)
        val id = o.optString("id").trim()
        if (id.isEmpty()) return null
        val bare = id.substringAfter(':').ifEmpty { id }
        return cleanIngredientName(bare.replace('-', ' '))
    }

    fun cleanIngredientName(raw: String, max: Int = 80): String? =
        raw.map { if (it.isISOControl()) ' ' else it }
            .filter { Character.getType(it) != Character.FORMAT.toInt() }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
            .take(max)
            .trim()
            .ifEmpty { "" }
            .takeIf { it.isNotEmpty() }

    /** True when structured OFF data yields a usable ingredient list for [servingGrams]. */
    fun isUsable(snap: OffSnapshot, servingGrams: Double = snap.servingGrams()): Boolean =
        fromStructured(snap.ingredientsJson, servingGrams).usable

    /**
     * Decide whether to ask the AI: no usable structured percents. Ingredients text alone
     * (ordered by weight) is passed to the model as a hint, not treated as usable alone.
     */
    fun needsAiGuess(snap: OffSnapshot, servingGrams: Double = snap.servingGrams()): Boolean =
        !isUsable(snap, servingGrams)
}
