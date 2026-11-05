package app.diurna.domain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientDensityTest {
    @Test fun normalizeNameLowercasesAndStrips() {
        assertEquals("all purpose flour", IngredientDensity.normalizeName("  All-Purpose Flour! "))
        assertEquals("onion chopped", IngredientDensity.normalizeName("Onion, chopped (optional)"))
        assertEquals("", IngredientDensity.normalizeName("   "))
    }

    @Test fun cacheKeyIncludesKind() {
        assertEquals(
            "flour|volume",
            IngredientDensity.cacheKey("Flour", DensityUnitKind.VOLUME),
        )
        assertEquals(
            "egg|each",
            IngredientDensity.cacheKey("Egg", DensityUnitKind.EACH),
        )
    }

    @Test fun prepModifiersExtracted() {
        assertEquals(setOf("chopped", "packed"), IngredientDensity.prepModifiers("packed brown sugar, chopped"))
    }

    @Test fun densityFromCupPortion() {
        // 125 g per 1 cup → 125/240 ≈ 0.521
        val portions = listOf(
            FdcPortion(125.0, 1.0, "cup", "", "1 cup"),
        )
        val d = IngredientDensity.densityFromPortions(portions)!!
        assertEquals(125.0 / 240.0, d, 0.001)
    }

    @Test fun densityPrefersCupOverTbspAndModifierMatch() {
        val portions = listOf(
            FdcPortion(8.0, 1.0, "tbsp", "", "1 tbsp"),
            FdcPortion(160.0, 1.0, "cup", "chopped", "1 cup, chopped"),
            FdcPortion(120.0, 1.0, "cup", "sliced", "1 cup, sliced"),
        )
        val d = IngredientDensity.densityFromPortions(portions, preferModifiers = setOf("chopped"))!!
        assertEquals(160.0 / 240.0, d, 0.001)
    }

    @Test fun gramsPerEachFromMedium() {
        val portions = listOf(
            FdcPortion(50.0, 1.0, "undetermined", "", "1 medium"),
            FdcPortion(60.0, 1.0, "undetermined", "", "1 large"),
        )
        assertEquals(50.0, IngredientDensity.gramsPerEachFromPortions(portions)!!, 0.01)
    }

    @Test fun parsePortionsFromJson() {
        val arr = JSONArray().put(
            JSONObject()
                .put("gramWeight", 125.0)
                .put("value", 1.0)
                .put("measureUnit", JSONObject().put("name", "cup").put("abbreviation", "cup"))
                .put("modifier", "sifted")
                .put("portionDescription", "1 cup"),
        )
        val p = IngredientDensity.parsePortionsFromJson(arr)
        assertEquals(1, p.size)
        assertEquals(125.0, p[0].gramWeight, 0.0)
        assertEquals("cup", p[0].unitName)
        assertEquals("sifted", p[0].modifier)
    }

    @Test fun matchBuiltinDensity() {
        assertEquals("flour", IngredientDensity.matchBuiltinDensity("all-purpose flour")?.id)
        assertEquals("oil", IngredientDensity.matchBuiltinDensity("olive oil")?.id)
        assertNull(IngredientDensity.matchBuiltinDensity("quinoa flakes xyz"))
    }

    @Test fun densityUnitKindOf() {
        assertEquals(DensityUnitKind.VOLUME, DensityUnitKind.of(IngredientUnit.CUP))
        assertEquals(DensityUnitKind.EACH, DensityUnitKind.of(IngredientUnit.EACH))
        assertNull(DensityUnitKind.of(IngredientUnit.G))
    }
}
