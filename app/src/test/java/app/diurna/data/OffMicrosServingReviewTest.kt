package app.diurna.data

import app.diurna.domain.NutrientId
import org.junit.Assert.*
import org.junit.Test

class OffMicrosServingReviewTest {
    private fun snap(nutrients: String, quantity: String = "30") = OffSnapshot(
        lookedUpAt = 1L, lookupOk = true,
        rawJson = """{"status":1,"product":{"serving_quantity":$quantity,"nutriments":$nutrients}}""",
    )
    @Test fun servingOnlyMicrosAreNotMistakenForHundredGrams() {
        val s = snap("""{"sodium_serving":0.03}""")
        assertEquals(30.0, OffMicrosBackfill.profileForGrams(s, 30.0)!![NutrientId.SODIUM]!!, 0.0001)
        assertEquals(100.0, OffMicrosBackfill.profileFromSnapshot(s)!![NutrientId.SODIUM]!!, 0.0001)
    }
    @Test fun perHundredGramsRespectsNonDefaultSnapshotBasis() {
        val s = snap("""{"sodium_100g":0.1}""").copy(basisGrams = 50.0)
        assertEquals(50.0, OffMicrosBackfill.profileFromSnapshot(s)!![NutrientId.SODIUM]!!, 0.0001)
        assertEquals(30.0, OffMicrosBackfill.profileForGrams(s, 30.0)!![NutrientId.SODIUM]!!, 0.0001)
    }
    @Test fun differentPortionsRoundTripMissingAndZero() {
        val original = snap("""{"sodium_100g":0.1,"iron_100g":0}""")
        val s = OffSnapshot.decode(original.encode())!!
        for (grams in listOf(15.0, 30.0, 60.0, 100.0, 150.0)) {
            val p = OffMicrosBackfill.profileForGrams(s, grams)!!
            assertEquals(grams, p[NutrientId.SODIUM]!!, 0.0001)
            assertEquals(0.0, p[NutrientId.IRON]!!, 0.0)
            assertNull(p[NutrientId.CALCIUM])
        }
        for (grams in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertNull(OffMicrosBackfill.profileForGrams(s, grams))
    }
}
