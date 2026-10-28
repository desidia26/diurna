package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IngredientUnitsTest {
    @Test fun gramsPassthrough() {
        assertEquals(125.0, IngredientUnits.toGrams(125.0, IngredientUnit.G)!!, 1e-9)
    }

    @Test fun volumeRefusesWithoutDensity() {
        assertNull(IngredientUnits.toGrams(1.0, IngredientUnit.CUP, densityGPerMl = null))
        assertNull(IngredientUnits.toGrams(15.0, IngredientUnit.ML, densityGPerMl = null))
        assertNull(IngredientUnits.toGrams(1.0, IngredientUnit.TBSP, densityGPerMl = null))
        assertNull(IngredientUnits.toGrams(1.0, IngredientUnit.TSP, densityGPerMl = null))
    }

    @Test fun cupWithWaterDensity() {
        // 1 cup × 240 ml × 1.00 g/ml = 240 g
        assertEquals(240.0, IngredientUnits.toGrams(1.0, IngredientUnit.CUP, densityGPerMl = 1.0)!!, 1e-9)
    }

    @Test fun tbspAndTsp() {
        assertEquals(15.0, IngredientUnits.toGrams(1.0, IngredientUnit.TBSP, densityGPerMl = 1.0)!!, 1e-9)
        assertEquals(5.0, IngredientUnits.toGrams(1.0, IngredientUnit.TSP, densityGPerMl = 1.0)!!, 1e-9)
    }

    @Test fun oilCupUsesDensity() {
        val oil = IngredientDensities.byId("oil")!!.gPerMl
        assertEquals(240.0 * oil, IngredientUnits.toGrams(1.0, IngredientUnit.CUP, densityGPerMl = oil)!!, 1e-9)
    }

    @Test fun eachNeedsGramsPerEach() {
        assertNull(IngredientUnits.toGrams(2.0, IngredientUnit.EACH, gramsPerEach = null))
        assertEquals(100.0, IngredientUnits.toGrams(2.0, IngredientUnit.EACH, gramsPerEach = 50.0)!!, 1e-9)
    }

    @Test fun citedDensitiesPresent() {
        assertEquals(1.00, IngredientDensities.byId("water")!!.gPerMl, 1e-9)
        assertEquals(0.92, IngredientDensities.byId("oil")!!.gPerMl, 1e-9)
        assertEquals(0.53, IngredientDensities.byId("flour")!!.gPerMl, 1e-9)
    }
}
