package app.diurna.data

import app.diurna.domain.MicronutrientProfile
import org.json.JSONException
import org.json.JSONObject

/**
 * Rebuild micronutrient profiles from a stored Open Food Facts snapshot without hitting the
 * network. Uses [OffSnapshot.rawJson] nutriments; prefers `_100g` keys (matching how we store
 * [OffSnapshot.basisGrams] = 100 when possible). Never invents values.
 */
object OffMicrosBackfill {
    fun profileFromSnapshot(snap: OffSnapshot): MicronutrientProfile? {
        if (!snap.lookupOk) return null
        val raw = snap.rawJson ?: return null
        return try {
            val root = JSONObject(raw)
            if (root.optInt("status", 1) == 0) return null
            val n = root.optJSONObject("product")?.optJSONObject("nutriments") ?: return null
            // Prefer per-100g; fall back to per-serving only when _100g energy/micros absent.
            when {
                hasAnyMicro(n, "_100g") -> parseOffMicronutrients(n, "_100g")
                hasAnyMicro(n, "_serving") -> parseOffMicronutrients(n, "_serving")
                else -> parseOffMicronutrients(n, "_100g")
            }
        } catch (_: JSONException) {
            null
        }
    }

    fun profileForGrams(snap: OffSnapshot, grams: Double): MicronutrientProfile? {
        if (grams <= 0 || !grams.isFinite()) return null
        val perBasis = profileFromSnapshot(snap) ?: return null
        val basis = snap.basisGrams.takeIf { it > 0 && it.isFinite() } ?: 100.0
        return perBasis.scale(grams / basis)
    }

    private fun hasAnyMicro(n: JSONObject, suffix: String): Boolean {
        val stems = listOf(
            "sodium", "sodium-mg", "salt",
            "potassium", "calcium", "iron", "magnesium", "zinc",
            "vitamin-a", "vitamin-a-rae", "vitamin-c", "vitamin-d", "vitamin-d-iu",
            "vitamin-b12", "folate-equivalent",
        )
        return stems.any { n.has("$it$suffix") }
    }
}
