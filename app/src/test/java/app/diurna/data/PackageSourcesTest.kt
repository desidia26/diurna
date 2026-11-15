package app.diurna.data

import app.diurna.ai.PackageReading
import app.diurna.data.fdc.FdcClient
import app.diurna.domain.PackageMerge
import app.diurna.domain.PackageMerge.Source
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each source becomes a merge input on the right basis; nothing missing turns into 0. */
class PackageSourcesTest {
    private fun off(product: String) = """{"status":1,"product":$product}"""

    @Test fun offPer100gIsPer100g() {
        val json = off("""{"serving_size":"30 g","serving_quantity":30,"ingredients_text":"Almonds",
            "nutriments":{"energy-kcal_100g":579,"proteins_100g":21,"fat_100g":50,"sodium_100g":0.001}}""")
        val i = offMergeInput(parseOffSnapshot(json, 1L)!!)!!
        assertEquals(Source.OFF, i.source)
        assertEquals(579.0, i.per100g.getValue("kcal"), 1e-9)
        assertEquals(1.0, i.per100g.getValue("sodium_mg"), 1e-9)
        assertTrue(i.perServing.isEmpty())
        assertEquals(30.0, i.servingGrams!!, 1e-9)
        assertEquals("Almonds", i.ingredients)
        assertNull(i.per100g["sugars_g"])
    }

    @Test fun offServingOnlyWithoutWeightStaysPerServing() {
        // OFF stores these per-serving numbers under a stand-in 100 g basis; they aren't per 100 g.
        val json = off("""{"serving_size":"1 bottle","nutriments":{"energy-kcal_serving":160,"proteins_serving":30}}""")
        val i = offMergeInput(parseOffSnapshot(json, 1L)!!)!!
        assertTrue(i.per100g.isEmpty())
        assertEquals(160.0, i.perServing.getValue("kcal"), 1e-9)
        assertNull(i.servingGrams)
    }

    @Test fun failedOffSnapshotIsNoInputButKeepsIngredientsWhenItHasThem() {
        assertNull(offMergeInput(OffSnapshot.failed(1L)))
        val withText = OffSnapshot(lookedUpAt = 1L, lookupOk = false, ingredientsText = "Oats, honey")
        val i = offMergeInput(withText)!!
        assertTrue(i.per100g.isEmpty() && i.perServing.isEmpty())
        assertEquals("Oats, honey", i.ingredients)
    }

    @Test fun fdcBrandedCarriesMicrosUnderSharedKeys() {
        val f = FdcClient.parseBranded(JSONObject("""{"fdcId":1,"description":"X","gtinUpc":"076991000411",
            "servingSize":30,"servingSizeUnit":"g","ingredients":"ALMONDS.",
            "foodNutrients":[{"nutrientId":1008,"value":567},{"nutrientId":1093,"value":0},{"nutrientId":1114,"value":0.5}]}"""))!!
        val i = fdcMergeInput(f)
        assertEquals(Source.FDC_BRANDED, i.source)
        assertEquals(567.0, i.per100g.getValue("kcal"), 1e-9)
        assertEquals(0.0, i.per100g.getValue("sodium_mg"), 0.0)
        assertEquals(0.5, i.per100g.getValue("vitamin_d_ug"), 1e-9)
        assertEquals(30.0, i.servingGrams!!, 1e-9)
    }

    @Test fun labelReadingIsPerServing() {
        val r = PackageReading(true, "Flax", "", "1 Tbsp (7g)", 7.0, 86.0, mapOf("kcal" to 45.0, "fat_g" to 3.0), "FLAXSEEDS")
        val i = labelMergeInput(r)
        assertEquals(Source.LABEL, i.source)
        assertEquals(45.0, i.perServing.getValue("kcal"), 1e-9)
        assertTrue(i.per100g.isEmpty())
        val m = PackageMerge.merge(listOf(i))
        assertEquals(642.86, m.per100g.getValue("kcal").value, 0.01)
    }
}
