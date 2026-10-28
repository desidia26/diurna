package app.diurna.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nutrition Facts label reader: a cereal panel, a drink with zeros, and refusal when it's not a label.
 */
class LabelReaderTest {
    private fun reading(
        product: String = "",
        serving: String = "",
        kcal: Int = 0,
        protein: Int = 0,
        carbs: Int = 0,
        fat: Int = 0,
        fiber: Int = 0,
        sugars: Int = 0,
        added: Int = 0,
        sat: Int = 0,
        trans: Int = 0,
        chol: Int = 0,
        note: String = "",
    ) = JSONObject()
        .put("product_name", product).put("serving", serving)
        .put("kcal", kcal).put("protein_g", protein).put("carbs_g", carbs).put("fat_g", fat)
        .put("fiber_g", fiber).put("sugars_g", sugars).put("added_sugars_g", added)
        .put("sat_fat_g", sat).put("trans_fat_g", trans).put("cholesterol_mg", chol)
        .put("note", note).toString()

    private val cereal = reading(
        product = "Cheerios", serving = "1 1/2 cup (39g)", kcal = 140,
        protein = 5, carbs = 29, fat = 2, fiber = 4, sugars = 2, added = 1, sat = 0, trans = 0, chol = 0,
    )

    @Test fun parsesACerealPanel() {
        val r = LabelPrompt.parse(cereal)
        assertEquals("Cheerios", r.productName)
        assertEquals("1 1/2 cup (39g)", r.serving)
        assertEquals(140, r.kcal)
        assertEquals(5, r.proteinG)
        assertEquals(29, r.carbsG)
        assertEquals(2, r.fatG)
        assertEquals(4, r.fiberG)
        assertEquals(2, r.sugarsG)
        assertEquals(1, r.addedSugarsG)
        assertEquals(0, r.satFatG)
        assertEquals(0, r.transFatG)
        assertEquals(0, r.cholesterolMg)
    }

    @Test fun keepsPrintedZeros() {
        val r = LabelPrompt.parse(reading(kcal = 10, protein = 0, carbs = 0, fat = 0, fiber = 0, sugars = 0, sat = 0, trans = 0, chol = 0, serving = "1 tsp"))
        assertEquals(0, r.proteinG)
        assertEquals(0, r.fiberG)
        assertEquals(0, r.transFatG)
        assertEquals(0, r.cholesterolMg)
    }

    @Test fun noCaloriesAsksToTypeIt() {
        val e = assertThrows(EstimateFailed::class.java) {
            LabelPrompt.parse(reading(note = "not a nutrition label"))
        }
        assertEquals("No calories found (not a nutrition label). Enter it by hand.", e.message)
    }

    @Test fun garbageFails() {
        assertThrows(EstimateFailed::class.java) { LabelPrompt.parse("<html>") }
        assertThrows(EstimateFailed::class.java) { LabelPrompt.parse("[1,2]") }
        assertThrows(EstimateFailed::class.java) { LabelPrompt.parse("""{"kcal":"lots"}""") }
    }

    @Test fun negativeNumbersAreDropped() {
        val r = LabelPrompt.parse(reading(kcal = 100, protein = -1, fiber = -3, sat = 2))
        assertEquals(0, r.proteinG)
        assertEquals(null, r.fiberG)
        assertEquals(2, r.satFatG)
    }

    @Test fun thePromptIsAboutNutritionFacts() {
        assertTrue(LabelPrompt.SYSTEM.contains("Nutrition Facts"))
        assertTrue(LabelPrompt.SYSTEM.contains("never invent"))
        assertTrue(LabelPrompt.USER_TEXT.contains("Nutrition Facts"))
    }
}
