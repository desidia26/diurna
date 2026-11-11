package app.diurna.ui

import app.diurna.data.MealEntity
import app.diurna.data.gramsPerServing
import app.diurna.data.parseOffProduct
import app.diurna.data.parseOffSnapshot
import app.diurna.domain.IngredientUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A barcode ingredient line must scale the product's per-serving numbers by the grams entered,
 * not treat them as per 100 g.
 */
class BarcodeIngredientScaleTest {
    private fun off(product: String) = """{"status":1,"product":$product}"""

    private fun draftFrom(json: String): MealEntity {
        val snap = parseOffSnapshot(json, 1L)!!
        return parseOffProduct(json)!!.copy(barcode = "0012345678905", offSnapshotJson = snap.encode())
    }

    @Test fun perServingWithServingQuantityScalesByServingWeight() {
        // 30 g serving: 120 kcal, 3 g protein. 60 g eaten = two servings.
        val meal = draftFrom(off("""{"product_name":"Crackers","serving_size":"30 g","serving_quantity":30,
            "nutriments":{"energy-kcal_serving":120,"proteins_serving":3,"carbohydrates_serving":20,"fat_serving":4,"fiber_serving":2}}"""))
        assertEquals(30.0, gramsPerServing(meal)!!, 1e-9)
        val d = barcodeIngredientDraft(meal, 60.0, source = "barcode")!!
        assertEquals(240, d.kcal)
        assertEquals(6, d.nutrition.proteinG)
        assertEquals(4, d.nutrition.fiberG)
        assertEquals(60.0, d.grams!!, 1e-9)
        assertEquals(IngredientUnit.G, d.unit)
        assertEquals("barcode", d.source)
        assertEquals("0012345678905", d.barcode)
    }

    @Test fun per100gWithServingQuantityStillScalesByGramsEaten() {
        // OFF has 500 kcal/100 g and a 40 g serving; the meal stores one 40 g serving (200 kcal).
        val meal = draftFrom(off("""{"product_name":"Granola","serving_size":"40 g","serving_quantity":40,
            "nutriments":{"energy-kcal_100g":500,"proteins_100g":10,"carbohydrates_100g":60,"fat_100g":20}}"""))
        assertEquals(200, meal.kcal)
        val d = barcodeIngredientDraft(meal, 100.0, source = "barcode")!!
        assertEquals(500, d.kcal)
        assertEquals(10, d.nutrition.proteinG)
    }

    @Test fun per100gWithoutServingIsPer100g() {
        val meal = draftFrom(off("""{"product_name":"Oats","nutriments":{"energy-kcal_100g":380,"proteins_100g":13,"carbohydrates_100g":66,"fat_100g":7}}"""))
        val d = barcodeIngredientDraft(meal, 50.0, source = "barcode")!!
        assertEquals(190, d.kcal)
    }

    @Test fun servingWithoutWeightIsRefusedInsteadOfGuessed() {
        // Per-serving numbers with a volume serving: grams per serving are unknown.
        val meal = draftFrom(off("""{"product_name":"Shake","serving_size":"1 bottle (325 ml)",
            "nutriments":{"energy-kcal_serving":160,"proteins_serving":30,"carbohydrates_serving":5,"fat_serving":3}}"""))
        assertNull(gramsPerServing(meal))
        assertNull(barcodeIngredientDraft(meal, 100.0, source = "barcode"))
    }

    @Test fun savedMealWithoutSnapshotUsesGramsInItsServingNote() {
        val meal = MealEntity(name = "Bar", kcal = 200, proteinG = 20, carbsG = 22, fatG = 7, note = "1 bar (50 g)", barcode = "0012345678905")
        val d = barcodeIngredientDraft(meal, 25.0, source = "meal")!!
        assertEquals(100, d.kcal)
        assertEquals(10, d.nutrition.proteinG)
    }

    @Test fun savedMealWithNoKnownWeightIsRefused() {
        val meal = MealEntity(name = "Soup", kcal = 200, proteinG = 10, carbsG = 20, fatG = 8, note = "1 bowl", barcode = "0012345678905")
        assertNull(barcodeIngredientDraft(meal, 100.0, source = "meal"))
    }

    @Test fun missingOptionalValuesStayUnknown() {
        val meal = draftFrom(off("""{"product_name":"Crackers","serving_size":"30 g","serving_quantity":30,
            "nutriments":{"energy-kcal_serving":120,"proteins_serving":3,"carbohydrates_serving":20,"fat_serving":4}}"""))
        val d = barcodeIngredientDraft(meal, 60.0, source = "barcode")!!
        assertNull(d.nutrition.fiberG)
        assertNull(d.nutrition.sugarsG)
    }
}
