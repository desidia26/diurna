package app.diurna.data

import app.diurna.domain.PackageMerge
import app.diurna.domain.PackageMerge.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a one-photo capture stores: per-100 g facts with their source, serving basis, ingredients. */
class CapturedFactsTest {
    private val merged = PackageMerge.merge(listOf(
        PackageMerge.Input(Source.LABEL, perServing = mapOf("kcal" to 200.0, "fat_g" to 20.0, "trans_fat_g" to 0.0),
            servingGrams = 28.0, servingText = "1 oz (28g)", ingredients = "PECANS."),
        PackageMerge.Input(Source.FDC_BRANDED, per100g = mapOf("kcal" to 714.0, "sodium_mg" to 0.0, "fat_g" to 50.0)),
    ))

    @Test fun roundTripsPer100gProvenanceDisagreementsAndBasis() {
        val c = CapturedFacts.from(merged)
        assertEquals("PECANS.", c.ingredientsText)
        assertEquals("label", c.ingredientsSource)
        assertEquals(28.0, c.servingGrams!!, 1e-9)
        assertEquals("1 oz (28g)", c.servingText)
        val back = c.facts()
        assertEquals(Source.LABEL, back.getValue("kcal").source)
        assertEquals(714.29, back.getValue("kcal").value, 0.01)
        assertEquals(Source.FDC_BRANDED, back.getValue("sodium_mg").source)
        assertEquals(0.0, back.getValue("trans_fat_g").value, 0.0) // printed zero stays zero
        assertEquals(listOf(Source.FDC_BRANDED), back.getValue("fat_g").disagreements.map { it.source })
        assertNull("unknown stays absent", back["sugars_g"])
        assertEquals(Source.LABEL, c.servingGramsSource())
        assertEquals(CapturedFacts.BASIS_PER_100G, c.basis())
    }

    @Test fun manualEditsAreRecordedAsManual() {
        val c = CapturedFacts.from(merged, manual = mapOf("sugars_g" to 3.6, "kcal" to 700.0))
        val f = c.facts()
        assertEquals(Source.MANUAL, f.getValue("sugars_g").source)
        assertEquals(Source.MANUAL, f.getValue("kcal").source)
        assertEquals(700.0, f.getValue("kcal").value, 1e-9)
        // The replaced label value stays visible as the disagreement it overrode.
        assertTrue(f.getValue("kcal").disagreements.any { it.source == Source.LABEL })
    }

    @Test fun manualNaNOrNegativeIsIgnored() {
        val f = CapturedFacts.from(merged, manual = mapOf("sugars_g" to Double.NaN, "fiber_g" to -1.0)).facts()
        assertNull(f["sugars_g"])
        assertNull(f["fiber_g"])
    }

    @Test fun corruptJsonDecodesAsNoFacts() {
        assertTrue(CapturedFacts(factsJson = "{not json").facts().isEmpty())
        assertTrue(CapturedFacts(factsJson = null).facts().isEmpty())
    }

    @Test fun unknownSourceWireIsDropped() {
        val c = CapturedFacts(factsJson = """{"basis":"per_100g","fields":{"kcal":{"v":100,"s":"martian"},"fat_g":{"v":1,"s":"off"}}}""")
        assertEquals(setOf("fat_g"), c.facts().keys)
    }

    @Test fun sourceWiresAreStable() {
        assertEquals(listOf("manual", "label", "fdc_branded", "off"), Source.entries.map { it.wire })
    }
}
