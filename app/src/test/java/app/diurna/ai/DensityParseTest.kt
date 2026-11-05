package app.diurna.ai

import app.diurna.domain.DensityUnitKind
import app.diurna.domain.IngredientDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DensityParseTest {
    @Test fun parseVolume() {
        val d = DensityPrompt.parse(
            """{"g_per_ml":0.53,"grams_per_each":null,"basis":"AP flour ~125g/cup","note":""}""",
            DensityUnitKind.VOLUME,
        )
        assertEquals(0.53, d.gPerMl!!, 0.0)
        assertEquals(IngredientDensity.Source.AI, d.source)
        assertTrue(d.isEstimate)
        assertEquals("AP flour ~125g/cup", d.basis)
    }

    @Test fun parseEach() {
        val d = DensityPrompt.parse(
            """{"g_per_ml":null,"grams_per_each":50,"basis":"medium egg","note":""}""",
            DensityUnitKind.EACH,
        )
        assertEquals(50.0, d.gramsPerEach!!, 0.0)
    }

    @Test fun missingValueParsesWithoutHasValue() {
        val d = DensityPrompt.parse(
            """{"g_per_ml":null,"grams_per_each":null,"basis":"","note":"unknown"}""",
            DensityUnitKind.VOLUME,
        )
        assertEquals(false, d.hasValue)
        assertEquals(IngredientDensity.Source.AI, d.source)
    }

    @Test fun clampsExtremeVolumeToNoValue() {
        val d = DensityPrompt.parse(
            """{"g_per_ml":9.0,"grams_per_each":null,"basis":"x","note":""}""",
            DensityUnitKind.VOLUME,
        )
        assertEquals(false, d.hasValue)
        assertEquals(null, d.gPerMl)
    }

    @Test fun rejectsMalformedJson() {
        assertThrows(EstimateFailed::class.java) {
            DensityPrompt.parse("not-json", DensityUnitKind.VOLUME)
        }
    }

    @Test fun schemaHasRequiredFields() {
        @Suppress("UNCHECKED_CAST")
        val req = DensityPrompt.SCHEMA["required"] as List<String>
        assertTrue(req.containsAll(listOf("g_per_ml", "grams_per_each", "basis", "note")))
    }
}
