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
}
