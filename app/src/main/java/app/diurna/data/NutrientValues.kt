package app.diurna.data

import app.diurna.domain.MicronutrientProfile
import app.diurna.domain.NutrientId
import app.diurna.domain.NutrientRegistry

object NutrientOwner {
    const val MEAL = "meal"
    const val FOOD_ENTRY = "food_entry"
    const val RECIPE_INGREDIENT = "recipe_ingredient"
    /** [ownerId] is the FDC fdcId. */
    const val FDC_FOOD = "fdc_food"
}

object NutrientSource {
    const val FDC = "fdc"
    const val OFF = "off"
    const val MANUAL = "manual"
}

object NutrientValues {
    fun toRows(
        ownerKind: String,
        ownerId: Long,
        profile: MicronutrientProfile,
        source: String,
        contributing: Int? = null,
        totalItems: Int? = null,
    ): List<NutrientValueEntity> =
        profile.asMap().map { (id, amount) ->
            NutrientValueEntity(
                ownerKind = ownerKind,
                ownerId = ownerId,
                nutrientId = id.id,
                amount = amount,
                unit = id.unit.wire,
                source = source,
                contributing = contributing,
                totalItems = totalItems,
            )
        }

    fun toProfile(rows: Iterable<NutrientValueEntity>): MicronutrientProfile {
        val pairs = ArrayList<Pair<NutrientId, Double>>()
        for (row in rows) {
            val id = NutrientId.fromId(row.nutrientId) ?: continue
            val amount = NutrientRegistry.sanitize(row.amount) ?: continue
            pairs += id to amount
        }
        return MicronutrientProfile.fromPairs(pairs)
    }
}
