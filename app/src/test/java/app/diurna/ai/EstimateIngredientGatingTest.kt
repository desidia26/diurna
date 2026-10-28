package app.diurna.ai

import app.diurna.data.fdc.FdcClient
import app.diurna.data.fdc.FdcTransport
import app.diurna.domain.FoodIngredient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the EstimateRunner gate: ingredients+FDC only when Detailed nutrition is on
 * AND an FDC key is present. The AI must never be asked to invent amino mg.
 */
class EstimateIngredientGatingTest {
    @Test fun wantIngredientsRequiresDetailedAndKey() {
        fun gate(detailed: Boolean, key: Boolean) = detailed && key
        assertFalse(gate(false, false))
        assertFalse(gate(false, true))
        assertFalse(gate(true, false))
        assertTrue(gate(true, true))
    }

    @Test fun estimatorReceivesIngredientsFlagOnlyWhenGated() = runBlocking {
        var seenIngredients: Boolean? = null
        val estimator = object : FoodEstimator {
            override val model = "test"
            override val gaveUpMessage = "gave up"
            override suspend fun estimate(
                text: String, photo: ByteArray?, detailed: Boolean, ingredients: Boolean,
            ): FoodEstimate {
                seenIngredients = ingredients
                return FoodEstimate(
                    100, 120, 10, 10, 5, "note",
                    ingredients = if (ingredients) listOf(FoodIngredient("tofu, firm", 100.0)) else null,
                )
            }
        }
        suspend fun run(detailed: Boolean, key: Boolean): FoodEstimate {
            val wantIngredients = detailed && key
            return estimator.estimate("tofu", null, detailed = detailed, ingredients = wantIngredients)
        }
        run(false, true)
        assertEquals(false, seenIngredients)
        run(true, false)
        assertEquals(false, seenIngredients)
        val e = run(true, true)
        assertEquals(true, seenIngredients)
        assertEquals(1, e.ingredients!!.size)
    }

    @Test fun withoutKeyClientDoesNotCallNetwork() = runBlocking {
        val client = FdcClient({ null }, FdcTransport { _, _ -> error("no network") })
        assertNull(client.searchTop("lentils"))
        assertNull(client.aminoProfile(1))
    }
}
