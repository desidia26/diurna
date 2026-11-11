package app.diurna.data

import app.diurna.ai.PackageReading
import app.diurna.data.fdc.FdcBrandedFood
import app.diurna.domain.MicronutrientProfile
import app.diurna.domain.PackageMerge
import org.json.JSONException
import org.json.JSONObject

/** Shared per-100 g key for a micronutrient, e.g. sodium_mg, vitamin_d_ug. */
private fun MicronutrientProfile.keyed(): Map<String, Double> =
    asMap().mapKeys { (id, _) -> "${id.id}_${id.unit.name.lowercase()}" }

/** The photo's panel, per serving as printed. */
fun labelMergeInput(r: PackageReading) = PackageMerge.Input(
    source = PackageMerge.Source.LABEL,
    perServing = r.perServing,
    servingGrams = r.servingGrams,
    servingText = r.servingText.ifEmpty { null },
    ingredients = r.ingredientsText,
)

fun fdcMergeInput(f: FdcBrandedFood) = PackageMerge.Input(
    source = PackageMerge.Source.FDC_BRANDED,
    per100g = f.per100 + (f.micros?.keyed() ?: emptyMap()),
    servingGrams = f.servingGrams,
    servingText = f.servingText,
    ingredients = f.ingredients,
)

/**
 * An OFF snapshot as a merge input, or null when it holds nothing. Its per100 values are only
 * per 100 g when OFF gave `_100g` numbers or a serving weight to convert from; per-serving-only
 * products (stored under a stand-in 100 g basis) stay per serving so they can't be misread.
 */
fun offMergeInput(s: OffSnapshot): PackageMerge.Input? {
    val ingredients = (s.ingredientsTextEn ?: s.ingredientsText)?.ifBlank { null }
    val product = s.rawJson?.let { raw -> try { JSONObject(raw).optJSONObject("product") } catch (_: JSONException) { null } }
    val nutriments = product?.optJSONObject("nutriments")
    val qty = product?.opt("serving_quantity")?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 1_000 }
    val servingGrams = qty ?: s.servingSize?.let(::gramsInText)
    val values: Map<String, Double> = if (!s.lookupOk || s.per100Json == null) emptyMap() else try {
        val o = JSONObject(s.per100Json)
        o.keys().asSequence().mapNotNull { k -> o.optDouble(k, Double.NaN).takeIf { it.isFinite() }?.let { k to it * 100.0 / s.basisGrams } }.toMap()
    } catch (_: JSONException) { emptyMap() }
    val realPer100 = nutriments?.let { n -> n.has("energy-kcal_100g") || n.has("energy_100g") } == true || qty != null
    if (values.isEmpty() && ingredients == null) return null
    val micros = nutriments?.let { n ->
        if (realPer100 && (n.has("energy-kcal_100g") || n.has("energy_100g"))) parseOffMicronutrients(n, "_100g")?.keyed() else null
    } ?: emptyMap()
    return if (realPer100 || values.isEmpty()) {
        PackageMerge.Input(PackageMerge.Source.OFF, per100g = values + micros, servingGrams = servingGrams,
            servingText = s.servingSize, ingredients = ingredients)
    } else {
        PackageMerge.Input(PackageMerge.Source.OFF, perServing = values, servingGrams = servingGrams,
            servingText = s.servingSize, ingredients = ingredients)
    }
}

private fun gramsInText(text: String): Double? =
    Regex("""(\d+(?:\.\d+)?)\s*g\b""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        ?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 1_000 }
