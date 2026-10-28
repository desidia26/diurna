package app.diurna.data.fdc

import app.diurna.data.FdcFoodCacheDao
import app.diurna.data.FdcFoodCacheEntity
import app.diurna.domain.AminoAcids
import app.diurna.domain.FoodIngredient

/**
 * Resolves AI ingredient names against FDC (Foundation + SR Legacy), caches per-100g profiles,
 * and sums scaled amino milligrams. Never invents amino values — miss → that ingredient skipped;
 * if nothing contributes, returns null aminos.
 */
class AminoResolver(
    private val client: FdcClient,
    private val cache: FdcFoodCacheDao,
) {
    data class Result(
        val aminos: AminoAcids?,
        val ingredients: List<FoodIngredient>,
    )

    suspend fun resolve(ingredients: List<FoodIngredient>): Result {
        if (ingredients.isEmpty()) return Result(null, ingredients)
        val matched = ArrayList<FoodIngredient>(ingredients.size)
        val parts = ArrayList<AminoAcids>()
        for (ing in ingredients) {
            val hit = lookup(ing.name)
            if (hit == null) {
                matched += ing
                continue
            }
            matched += ing.copy(fdcId = hit.fdcId, matchedDescription = hit.description)
            parts += hit.forGrams(ing.grams)
        }
        val summed = if (parts.isEmpty()) null else AminoAcids.sum(parts)
        return Result(summed?.takeIf { it.hasAny }, matched)
    }

    private suspend fun lookup(name: String): AminoProfile? {
        val key = name.trim().lowercase()
        if (key.isEmpty()) return null
        cache.get(key)?.let { row ->
            return AminoProfile(row.fdcId, row.description, row.toAminos())
        }
        val hit = client.searchTop(name) ?: return null
        val profile = client.aminoProfile(hit.fdcId) ?: return null
        cache.upsert(
            FdcFoodCacheEntity(
                queryKey = key,
                fdcId = profile.fdcId,
                description = profile.description.ifBlank { hit.description },
                hisMgPer100 = profile.aminos.hisMg,
                ileMgPer100 = profile.aminos.ileMg,
                leuMgPer100 = profile.aminos.leuMg,
                lysMgPer100 = profile.aminos.lysMg,
                metMgPer100 = profile.aminos.metMg,
                pheMgPer100 = profile.aminos.pheMg,
                thrMgPer100 = profile.aminos.thrMg,
                trpMgPer100 = profile.aminos.trpMg,
                valMgPer100 = profile.aminos.valMg,
                cysMgPer100 = profile.aminos.cysMg,
                tyrMgPer100 = profile.aminos.tyrMg,
                cachedAt = System.currentTimeMillis(),
            ),
        )
        return profile.copy(description = profile.description.ifBlank { hit.description })
    }
}

private fun FdcFoodCacheEntity.toAminos() = AminoAcids(
    hisMgPer100, ileMgPer100, leuMgPer100, lysMgPer100, metMgPer100, pheMgPer100,
    thrMgPer100, trpMgPer100, valMgPer100, cysMgPer100, tyrMgPer100,
)
