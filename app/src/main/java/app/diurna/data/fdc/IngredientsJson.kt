package app.diurna.data.fdc

import app.diurna.domain.FoodIngredient
import org.json.JSONArray
import org.json.JSONObject

object IngredientsJson {
    fun encode(list: List<FoodIngredient>?): String? {
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
        return arr.toString()
    }

    fun decode(raw: String?): List<FoodIngredient>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
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
            }.ifEmpty { null }
        }.getOrNull()
    }
}
