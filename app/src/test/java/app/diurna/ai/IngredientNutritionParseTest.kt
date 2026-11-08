package app.diurna.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IngredientNutritionParseTest {
    @Test fun parsesFlourMacros() {
        val p = IngredientNutritionPrompt.parse(
            """{"kcal_per_100":364,"protein_g_per_100":10,"carbs_g_per_100":76,"fat_g_per_100":1,"fiber_g_per_100":3,"sugars_g_per_100":null,"basis":"AP flour","note":""}""",
        )
        assertEquals(364, p.kcalPer100)
        assertEquals(10, p.per100.proteinG)
        assertEquals(76, p.per100.carbsG)
        assertEquals(1, p.per100.fatG)
        assertEquals(3, p.per100.fiberG)
    }

    @Test fun parsesGenuineZerosForWater() {
        val p = IngredientNutritionPrompt.parse(
            """{"kcal_per_100":0,"protein_g_per_100":0,"carbs_g_per_100":0,"fat_g_per_100":0,"fiber_g_per_100":0,"sugars_g_per_100":0,"basis":"water","note":""}""",
        )
        assertEquals(0, p.kcalPer100)
        assertEquals(0, p.per100.proteinG)
        assertEquals(0, p.per100.carbsG)
        assertEquals(0, p.per100.fatG)
    }

    @Test(expected = EstimateFailed::class)
    fun missingMacroIsParseFailure() {
        IngredientNutritionPrompt.parse(
            """{"kcal_per_100":null,"protein_g_per_100":10,"carbs_g_per_100":76,"fat_g_per_100":1,"fiber_g_per_100":null,"sugars_g_per_100":null,"basis":"x","note":""}""",
        )
    }

    @Test(expected = EstimateFailed::class)
    fun garbageJsonIsParseFailure() {
        IngredientNutritionPrompt.parse("not json")
    }

    @Test fun userTextTruncatesLongNames() {
        val t = IngredientNutritionPrompt.userText("x".repeat(200))
        assertTrue(t.contains("Ingredient:"))
        assertTrue(t.length < 200)
    }
}
