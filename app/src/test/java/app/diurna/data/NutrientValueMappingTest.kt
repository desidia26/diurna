package app.diurna.data

import app.diurna.domain.MicronutrientProfile
import app.diurna.domain.NutrientId
import app.diurna.domain.NutrientUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrientValueMappingTest {

    @Test fun rowsRoundTripThroughProfile() {
        val profile = MicronutrientProfile.of(
            NutrientId.SODIUM to 12.5,
            NutrientId.FOLATE_DFE to 80.0,
        )
        val rows = NutrientValues.toRows(
            ownerKind = NutrientOwner.FOOD_ENTRY,
            ownerId = 42L,
            profile = profile,
            source = NutrientSource.OFF,
        )
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.ownerKind == NutrientOwner.FOOD_ENTRY && it.ownerId == 42L })
        assertTrue(rows.all { it.source == NutrientSource.OFF })
        val sodium = rows.first { it.nutrientId == NutrientId.SODIUM.id }
        assertEquals(12.5, sodium.amount, 0.0)
        assertEquals(NutrientUnit.MG.name.lowercase(), sodium.unit)
        val folate = rows.first { it.nutrientId == NutrientId.FOLATE_DFE.id }
        assertEquals("ug", folate.unit)
        val back = NutrientValues.toProfile(rows)
        assertEquals(12.5, back[NutrientId.SODIUM]!!, 0.0)
        assertEquals(80.0, back[NutrientId.FOLATE_DFE]!!, 0.0)
    }

    @Test fun ignoresUnknownNutrientIdsOnRead() {
        val rows = listOf(
            NutrientValueEntity(
                ownerKind = NutrientOwner.MEAL,
                ownerId = 1L,
                nutrientId = "not_a_real_nutrient",
                amount = 1.0,
                unit = "mg",
                source = NutrientSource.OFF,
            ),
            NutrientValueEntity(
                ownerKind = NutrientOwner.MEAL,
                ownerId = 1L,
                nutrientId = NutrientId.IRON.id,
                amount = 2.0,
                unit = "mg",
                source = NutrientSource.FDC,
            ),
        )
        val p = NutrientValues.toProfile(rows)
        assertEquals(2.0, p[NutrientId.IRON]!!, 0.0)
        assertEquals(1, p.asMap().size)
    }
}
