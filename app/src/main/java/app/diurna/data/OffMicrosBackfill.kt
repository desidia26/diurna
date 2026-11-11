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
            val basis = snap.basisGrams.takeIf { it > 0 && it.isFinite() } ?: return null
            // The suffix, not the macro snapshot's basis, determines the raw micro units.
            val per100 = parseOffMicronutrients(n, "_100g")
            if (per100 != null) per100.scale(basis / 100.0)
            else {
                val serving = snap.servingGrams().takeIf { it > 0 && it.isFinite() } ?: return null
                parseOffMicronutrients(n, "_serving")?.scale(basis / serving)
            }
        } catch (_: JSONException) {
            null
        }
    }

    fun profileForGrams(snap: OffSnapshot, grams: Double): MicronutrientProfile? {
        if (grams <= 0 || !grams.isFinite()) return null
        val perBasis = profileFromSnapshot(snap) ?: return null
        val basis = snap.basisGrams.takeIf { it > 0 && it.isFinite() } ?: return null
        return perBasis.scale(grams / basis)
    }

}
