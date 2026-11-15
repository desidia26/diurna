package app.diurna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealFromPhotoTest {
    @Test fun `default name lists what went in`() {
        assertEquals("Meal from photo", MealFromPhoto.defaultName(emptyList()))
        assertEquals("Oats", MealFromPhoto.defaultName(listOf(" Oats ", "")))
        assertEquals("Oats and almonds", MealFromPhoto.defaultName(listOf("Oats", "almonds")))
        assertEquals("Oats, almonds and pecans", MealFromPhoto.defaultName(listOf("Oats", "almonds", "pecans", "pecans")))
        assertEquals("Oats, almonds, pecans + 2 more", MealFromPhoto.defaultName(listOf("Oats", "almonds", "pecans", "flax", "craisins")))
    }

    @Test fun `long names are cut to fit`() {
        val n = MealFromPhoto.defaultName(listOf("Old Fashioned 100% Whole Grain Rolled Oats", "Natural Sliced Almonds Blanched"))
        assertTrue(n.length <= MealFromPhoto.MAX_NAME)
        assertTrue(n.endsWith("…"))
    }

    @Test fun `summary lines and total`() {
        val oats = IngredientDraft(name = "Oats", amount = 40.0, grams = 40.0, kcal = 150)
        val milk = IngredientDraft(name = "", amount = 0.5, unit = app.diurna.domain.IngredientUnit.CUP, kcal = 60)
        assertEquals("Oats · 40 g · 150 kcal", MealFromPhoto.line(oats))
        assertEquals("Unnamed · 0.5 cup · 60 kcal", MealFromPhoto.line(milk))
        assertEquals(210, MealFromPhoto.totalKcal(listOf(oats, milk)))
    }
}
