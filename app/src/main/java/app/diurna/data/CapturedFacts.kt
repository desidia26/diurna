package app.diurna.data

import androidx.room.ColumnInfo
import app.diurna.domain.PackageMerge
import app.diurna.domain.PackageMerge.Source
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What a one-photo capture found, stored on the meal or recipe line it filled. Every column is
 * nullable: a field nobody read stays null, never 0.
 *
 * [factsJson]: `{"basis":"per_100g","serving_grams_source":"label","flags":[…],
 * "fields":{"kcal":{"v":714.3,"s":"label","alt":[{"s":"off","v":700}]}, …}}`. Values are per
 * 100 g; `s` is a [Source.wire] (manual / label / fdc_branded / off); `alt` holds disagreeing
 * lower sources shown on the confirm screen; `label_pending` marks a capture whose label wasn't read. The line's own macro columns stay the snapshot
 * for the amount used, as for every other ingredient.
 */
data class CapturedFacts(
    @ColumnInfo(name = "ingredients_text") val ingredientsText: String? = null,
    /** [Source.wire] of [ingredientsText]. */
    @ColumnInfo(name = "ingredients_source") val ingredientsSource: String? = null,
    /** Grams in one serving, when any source knew it. */
    @ColumnInfo(name = "serving_grams") val servingGrams: Double? = null,
    /** Serving line as printed, e.g. "1 oz (28g)". */
    @ColumnInfo(name = "serving_text") val servingText: String? = null,
    @ColumnInfo(name = "facts_json") val factsJson: String? = null,
) {
    fun basis(): String? = obj()?.optString("basis")?.ifEmpty { null }

    fun servingGramsSource(): Source? = Source.ofWire(obj()?.optString("serving_grams_source"))

    fun flags(): Set<PackageMerge.Flag> = obj()?.optJSONArray("flags")?.let { a ->
        (0 until a.length()).mapNotNull { i -> PackageMerge.Flag.entries.firstOrNull { it.name == a.optString(i) } }.toSet()
    } ?: emptySet()

    /** The label wasn't read yet (offline / no AI key / failed read); a later label photo can fill it in. */
    fun labelPending(): Boolean = obj()?.optBoolean("label_pending", false) == true

    /** Per-100 g facts by key; empty when none were stored or the JSON is unreadable. */
    fun facts(): Map<String, PackageMerge.Fact> {
        val fields = obj()?.optJSONObject("fields") ?: return emptyMap()
        val out = linkedMapOf<String, PackageMerge.Fact>()
        for (key in fields.keys()) {
            val f = fields.optJSONObject(key) ?: continue
            val v = f.optDouble("v", Double.NaN).takeIf { it.isFinite() && it >= 0 } ?: continue
            val src = Source.ofWire(f.optString("s")) ?: continue
            val alts = f.optJSONArray("alt")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val av = o.optDouble("v", Double.NaN).takeIf { it.isFinite() && it >= 0 } ?: return@mapNotNull null
                    Source.ofWire(o.optString("s"))?.let { PackageMerge.Other(it, av) }
                }
            }.orEmpty()
            out[key] = PackageMerge.Fact(v, src, alts)
        }
        return out
    }

    private fun obj(): JSONObject? = factsJson?.let { try { JSONObject(it) } catch (_: JSONException) { null } }

    companion object {
        const val BASIS_PER_100G = "per_100g"
        private const val MAX_TEXT = 2_000

        /**
         * From a merge, with per-100 g values the person typed on the confirm screen in [manual]
         * (recorded as [Source.MANUAL], keeping the value they replaced as a disagreement).
         */
        fun from(m: PackageMerge.Merged, manual: Map<String, Double> = emptyMap(), labelPending: Boolean = false): CapturedFacts {
            val facts = LinkedHashMap(m.per100g)
            manual.forEach { (k, v) ->
                if (!v.isFinite() || v < 0) return@forEach
                val prior = facts[k]
                val alts = prior?.let { listOf(PackageMerge.Other(it.source, it.value)) + it.disagreements }.orEmpty()
                facts[k] = PackageMerge.Fact(v, Source.MANUAL, alts.filter { it.value != v })
            }
            val fields = JSONObject()
            facts.forEach { (k, f) ->
                val o = JSONObject().put("v", f.value).put("s", f.source.wire)
                if (f.disagreements.isNotEmpty()) {
                    o.put("alt", JSONArray().apply { f.disagreements.forEach { put(JSONObject().put("s", it.source.wire).put("v", it.value)) } })
                }
                fields.put(k, o)
            }
            val json = JSONObject().put("basis", BASIS_PER_100G).put("fields", fields)
            m.servingGrams?.let { json.put("serving_grams_source", it.source.wire) }
            if (m.flags.isNotEmpty()) json.put("flags", JSONArray(m.flags.map { it.name }.sorted()))
            if (labelPending) json.put("label_pending", true)
            return CapturedFacts(
                ingredientsText = m.ingredients?.first?.take(MAX_TEXT),
                ingredientsSource = m.ingredients?.second?.wire,
                servingGrams = m.servingGrams?.value,
                servingText = m.servingText?.take(60),
                factsJson = json.toString(),
            )
        }
    }
}
