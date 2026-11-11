package app.diurna.domain

import app.diurna.domain.PackageMerge.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Label > USDA branded > Open Food Facts, per field, with provenance and flagged disagreements. */
class PackageMergeTest {
    // Pecans, per serving of 28 g as printed.
    private val label = PackageMerge.Input(
        source = Source.LABEL,
        perServing = mapOf("kcal" to 200.0, "fat_g" to 20.0, "protein_g" to 3.0, "carbs_g" to 4.0, "trans_fat_g" to 0.0),
        servingGrams = 28.0, servingText = "1 oz (28g)", ingredients = "PECANS.",
    )
    // Same product per 100 g in USDA branded: agrees on fat, has sodium the photo didn't show.
    private val fdc = PackageMerge.Input(
        source = Source.FDC_BRANDED,
        per100g = mapOf("kcal" to 714.0, "fat_g" to 71.4, "protein_g" to 10.7, "sodium_mg" to 0.0, "sugars_g" to 3.6),
        servingGrams = 28.0, ingredients = "PECANS",
    )
    // Crowd-sourced, with a wrong protein value.
    private val off = PackageMerge.Input(
        source = Source.OFF,
        per100g = mapOf("kcal" to 700.0, "protein_g" to 30.0, "fiber_g" to 9.6),
        servingGrams = 30.0, ingredients = "pecans",
    )

    @Test fun labelWinsEachFieldItHasAndPer100gIsDerivedFromItsServing() {
        val m = PackageMerge.merge(listOf(off, fdc, label))
        val kcal = m.per100g.getValue("kcal")
        assertEquals(Source.LABEL, kcal.source)
        assertEquals(714.29, kcal.value, 0.01)
        assertEquals(Source.LABEL, m.servingGrams!!.source)
        assertEquals(28.0, m.servingGrams!!.value, 1e-9)
        assertEquals("PECANS." to Source.LABEL, m.ingredients)
    }

    @Test fun lowerSourcesFillOnlyWhatHigherOnesLack() {
        val m = PackageMerge.merge(listOf(label, fdc, off))
        assertEquals(Source.FDC_BRANDED, m.per100g.getValue("sodium_mg").source)
        assertEquals(Source.FDC_BRANDED, m.per100g.getValue("sugars_g").source)
        assertEquals(Source.OFF, m.per100g.getValue("fiber_g").source)
    }

    @Test fun missingStaysUnknownAndPrintedZeroStaysZero() {
        val m = PackageMerge.merge(listOf(label))
        assertNull(m.per100g["sodium_mg"])
        assertEquals(0.0, m.per100g.getValue("trans_fat_g").value, 0.0)
        val eaten = PackageMerge.forGrams(m, 56.0)
        assertFalse(eaten.containsKey("sodium_mg"))
        assertEquals(0.0, eaten.getValue("trans_fat_g"), 0.0)
    }

    @Test fun disagreementsAreFlaggedNotAveraged() {
        val m = PackageMerge.merge(listOf(label, fdc, off))
        val protein = m.per100g.getValue("protein_g")
        assertEquals(Source.LABEL, protein.source)
        assertEquals(10.71, protein.value, 0.01)
        assertEquals(listOf(Source.OFF), protein.disagreements.map { it.source }) // FDC 10.7 agrees
        assertEquals(30.0, protein.disagreements.single().value, 1e-9)
        assertTrue("small rounding differences don't count", m.per100g.getValue("kcal").disagreements.isEmpty())
        assertTrue(m.servingGrams!!.disagreements.any { it.source == Source.OFF })
        assertTrue(m.hasDisagreements)
    }

    @Test fun labelWithoutServingGramsBorrowsTheNextSourcesAndSaysSo() {
        val noGrams = label.copy(servingGrams = null, servingText = "1/4 cup")
        val m = PackageMerge.merge(listOf(noGrams, fdc))
        assertEquals(Source.FDC_BRANDED, m.servingGrams!!.source)
        assertEquals(Source.LABEL, m.per100g.getValue("kcal").source)
        assertTrue(PackageMerge.Flag.SERVING_WEIGHT_FROM_DATABASE in m.flags)
    }

    @Test fun labelWithNoServingWeightAnywhereCantBePer100g() {
        val m = PackageMerge.merge(listOf(label.copy(servingGrams = null)))
        assertTrue(m.per100g.isEmpty())
        assertNull(m.servingGrams)
        assertTrue(PackageMerge.Flag.LABEL_NEEDS_SERVING_WEIGHT in m.flags)
        assertEquals("PECANS." to Source.LABEL, m.ingredients) // ingredients still harvested
        assertNull(PackageMerge.forServings(m, 1.0))
    }

    @Test fun caloriesThatDontMatchTheMacrosAreFlagged() {
        val bad = label.copy(perServing = mapOf("kcal" to 20.0, "fat_g" to 20.0, "protein_g" to 3.0, "carbs_g" to 4.0))
        assertTrue(PackageMerge.Flag.KCAL_MACROS_MISMATCH in PackageMerge.merge(listOf(bad)).flags)
        assertFalse(PackageMerge.Flag.KCAL_MACROS_MISMATCH in PackageMerge.merge(listOf(label)).flags)
    }

    @Test fun quantityInGramsOrServings() {
        val m = PackageMerge.merge(listOf(label))
        assertEquals(400.0, PackageMerge.forGrams(m, 56.0).getValue("kcal"), 1e-6)
        assertEquals(300.0, PackageMerge.forServings(m, 1.5)!!.getValue("kcal"), 1e-6)
        assertTrue(PackageMerge.forGrams(m, 0.0).isEmpty())
        assertTrue(PackageMerge.forGrams(m, Double.NaN).isEmpty())
    }

    @Test fun nothingFoundIsEmptyNotZeros() {
        val m = PackageMerge.merge(emptyList())
        assertTrue(m.per100g.isEmpty())
        assertNull(m.servingGrams)
        assertNull(m.ingredients)
        assertFalse(m.hasNutrition)
    }

    @Test fun badValuesFromAnySourceAreIgnored() {
        val junk = PackageMerge.Input(Source.OFF, per100g = mapOf("kcal" to -5.0, "fat_g" to Double.NaN, "protein_g" to 150.0))
        val m = PackageMerge.merge(listOf(junk))
        assertTrue(m.per100g.isEmpty())
    }
}
