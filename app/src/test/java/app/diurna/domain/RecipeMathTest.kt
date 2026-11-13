package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeMathTest {
    @Test fun perServingSumsAndDivides() {
        val batch = listOf(
            RecipeMath.IngredientNutrition(proteinG = 20, carbsG = 10, fatG = 5),
            RecipeMath.IngredientNutrition(proteinG = 10, carbsG = 30, fatG = 5, fiberG = 4),
        )
        val t = RecipeMath.perServing(batch, servingsYield = 2.0)
        assertEquals(15, t.proteinG) // (20+10)/2
        assertEquals(20, t.carbsG)
        assertEquals(5, t.fatG)
        assertEquals(2, t.fiberG) // 4/2
        // kcal from MacroEnergy on per-serving macros
        val expected = MacroEnergy.kcal(15, 20, 5, 2).total.toInt()
        assertEquals(expected, t.kcal)
    }

    @Test fun optionalFieldNullWhenAllUnknown() {
        val t = RecipeMath.perServing(
            listOf(RecipeMath.IngredientNutrition(proteinG = 10, carbsG = 0, fatG = 0)),
            1.0,
        )
        assertNull(t.fiberG)
        assertNull(t.sugarsG)
    }

    @Test fun aminoPartialCoverage() {
        val with = RecipeMath.IngredientNutrition(proteinG = 10, aminos = AminoAcids(lysMg = 500))
        val without = RecipeMath.IngredientNutrition(proteinG = 5)
        val t = RecipeMath.perServing(listOf(with, without), 1.0)
        assertEquals(1, t.aminoMatched)
        assertEquals(2, t.aminoTotal)
        assertTrue(t.aminoPartial)
        assertEquals(500, t.aminos.lysMg)
    }

    @Test fun aminoFullCoverageNotPartial() {
        val a = RecipeMath.IngredientNutrition(aminos = AminoAcids(lysMg = 100))
        val b = RecipeMath.IngredientNutrition(aminos = AminoAcids(hisMg = 50))
        val t = RecipeMath.perServing(listOf(a, b), 1.0)
        assertEquals(2, t.aminoMatched)
        assertFalse(t.aminoPartial)
    }

    @Test fun scalePer100() {
        val per100 = RecipeMath.IngredientNutrition(proteinG = 10, carbsG = 20, fatG = 5, fiberG = 4)
        val half = RecipeMath.scalePer100(per100, 50.0)
        assertEquals(5, half.proteinG)
        assertEquals(10, half.carbsG)
        assertEquals(2, half.fiberG)
    }

    @Test fun prefersLabeledKcalWhenEveryLineHasOne() {
        // Same numbers as Meal from photo's oats + almonds: labels 150+85=235,
        // while MacroEnergy on the macros would be 239 (fiber at 2 kcal/g).
        val oats = RecipeMath.IngredientNutrition(proteinG = 5, carbsG = 27, fatG = 3, fiberG = 4, kcal = 150)
        val almonds = RecipeMath.IngredientNutrition(proteinG = 3, carbsG = 3, fatG = 8, fiberG = 2, kcal = 85)
        val t = RecipeMath.perServing(listOf(oats, almonds), 1.0)
        assertEquals(235, t.kcal)
        assertEquals(8, t.proteinG)
        assertEquals(30, t.carbsG)
        assertEquals(11, t.fatG)
        assertEquals(6, t.fiberG)
        val halved = RecipeMath.perServing(listOf(oats, almonds), 2.0)
        assertEquals(118, halved.kcal) // 235/2 rounded
    }

    @Test fun derivesKcalFromMacrosWhenAnyLineOmitsIt() {
        val labeled = RecipeMath.IngredientNutrition(proteinG = 5, carbsG = 27, fatG = 3, fiberG = 4, kcal = 150)
        val macrosOnly = RecipeMath.IngredientNutrition(proteinG = 3, carbsG = 3, fatG = 8, fiberG = 2)
        val t = RecipeMath.perServing(listOf(labeled, macrosOnly), 1.0)
        val expected = MacroEnergy.kcal(8, 30, 11, 6).total.toInt()
        assertEquals(239, expected)
        assertEquals(expected, t.kcal)
    }

    @Test fun waterAtZeroKcalStillLetsNeighborsKeepTheirLabels() {
        val water = RecipeMath.IngredientNutrition(kcal = 0)
        val oats = RecipeMath.IngredientNutrition(proteinG = 5, carbsG = 27, fatG = 3, kcal = 150)
        assertEquals(150, RecipeMath.perServing(listOf(water, oats), 1.0).kcal)
    }

    @Test fun explicitKcalTreatsZeroWithMacrosAsUnknown() {
        assertEquals(150, RecipeMath.explicitKcal(150, 5, 27, 3))
        assertEquals(0, RecipeMath.explicitKcal(0, 0, 0, 0))
        assertNull(RecipeMath.explicitKcal(0, 5, 27, 3))
    }

    @Test fun scalePer100ScalesLabeledKcal() {
        val per100 = RecipeMath.IngredientNutrition(proteinG = 10, carbsG = 20, fatG = 5, kcal = 200)
        assertEquals(100, RecipeMath.scalePer100(per100, 50.0).kcal)
        assertNull(RecipeMath.scalePer100(RecipeMath.IngredientNutrition(proteinG = 10), 50.0).kcal)
    }
}
