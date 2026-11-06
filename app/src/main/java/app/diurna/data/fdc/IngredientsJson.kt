package app.diurna.data.fdc

import app.diurna.domain.FoodIngredient
import org.json.JSONArray
import org.json.JSONObject

object IngredientsJson {
    data class Encoded(
        val ingredients: List<FoodIngredient>,
        /** "off" | "ai" when known; null for older array-only rows. */
        val source: String? = null,
    )

    fun encode(list: List<FoodIngredient>?, source: String? = null): String? {
        if (list.isNullOrEmpty()) return null
        val arr = JSONArray()
        for (i in list) {
            arr.put(JSONObject().apply {
                put("name", i.name)
                put("grams", i.grams)
                if (i.fdcId != null) put("fdcId", i.fdcId)
                if (i.matchedDescription != null) put("matched", i.matchedDescription)
            })
        }
        if (source.isNullOrBlank()) return arr.toString()
        return JSONObject().put("source", source).put("ingredients", arr).toString()
    }

    fun decode(raw: String?): List<FoodIngredient>? = decodeWithSource(raw)?.ingredients

    fun decodeWithSource(raw: String?): Encoded? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                val o = JSONObject(trimmed)
                val arr = o.optJSONArray("ingredients") ?: return@runCatching null
                val list = readArray(arr) ?: return@runCatching null
                Encoded(list, o.optString("source").ifBlank { null })
            } else {
                val list = readArray(JSONArray(trimmed)) ?: return@runCatching null
                Encoded(list, source = null)
            }
        }.getOrNull()
    }

    private fun readArray(arr: JSONArray): List<FoodIngredient>? {
        val out = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name").trim()
                if (name.isEmpty()) continue
                add(
                    FoodIngredient(
                        name = name,
                        grams = o.optDouble("grams", 0.0),
                        fdcId = o.optInt("fdcId").takeIf { o.has("fdcId") && it > 0 },
                        matchedDescription = o.optString("matched").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        return out.ifEmpty { null }
    }
}
