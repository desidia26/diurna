package app.diurna.data

import app.diurna.domain.NutrientId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safe backfill of micros from a stored [OffSnapshot.rawJson]. Only fills when lookupOk and
 * raw JSON still has nutriments; amounts are for [OffSnapshot.basisGrams] of product.
 */
class OffMicrosBackfillTest {

    private fun offProduct(nutriments: String) = """
        {"status":1,"product":{"product_name":"Oats","nutriments":$nutriments}}
    """.trimIndent()

    @Test fun extractsPerBasisFromRawJson() {
        val snap = OffSnapshot(
            lookedUpAt = 1L,
            lookupOk = true,
            basisGrams = 100.0,
            rawJson = offProduct(
                """{"sodium_100g":0.01,"vitamin-c_100g":2.0,"iron_100g":0.001}""",
            ),
        )
        val p = OffMicrosBackfill.profileFromSnapshot(snap)!!
        assertEquals(10.0, p[NutrientId.SODIUM]!!, 0.01)
        assertEquals(2.0, p[NutrientId.VITAMIN_C]!!, 0.0)
        assertEquals(1.0, p[NutrientId.IRON]!!, 0.01)
    }

    @Test fun scalesToGramsEaten() {
        val snap = OffSnapshot(
            lookedUpAt = 1L,
            lookupOk = true,
            basisGrams = 100.0,
            rawJson = offProduct("""{"sodium_100g":0.1}"""),
        )
        val for40 = OffMicrosBackfill.profileForGrams(snap, 40.0)!!
        assertEquals(40.0, for40[NutrientId.SODIUM]!!, 0.01) // 100mg/100g × 40g
    }

    @Test fun skipsFailedOrMissingRaw() {
        assertNull(OffMicrosBackfill.profileFromSnapshot(OffSnapshot.failed()))
        assertNull(
            OffMicrosBackfill.profileFromSnapshot(
                OffSnapshot(lookedUpAt = 1L, lookupOk = true, rawJson = null),
            ),
        )
        assertNull(
            OffMicrosBackfill.profileFromSnapshot(
                OffSnapshot(
                    lookedUpAt = 1L,
                    lookupOk = true,
                    rawJson = """{"status":1,"product":{"nutriments":{}}}""",
                ),
            ),
        )
    }

    @Test fun saltFallbackStillApplies() {
        val snap = OffSnapshot(
            lookedUpAt = 1L,
            lookupOk = true,
            basisGrams = 100.0,
            rawJson = offProduct("""{"salt_100g":0.5}"""),
        )
        val p = OffMicrosBackfill.profileFromSnapshot(snap)!!
        assertEquals(200.0, p[NutrientId.SODIUM]!!, 0.01) // 0.5g salt × 0.4 × 1000
    }

    @Test fun vitaminAIuAloneStillRejected() {
        val snap = OffSnapshot(
            lookedUpAt = 1L,
            lookupOk = true,
            rawJson = offProduct("""{"vitamin-a-iu_100g":500}"""),
        )
        assertNull(OffMicrosBackfill.profileFromSnapshot(snap))
    }

    @Test fun prefersHundredGSuffixWhenPresent() {
        val snap = OffSnapshot(
            lookedUpAt = 1L,
            lookupOk = true,
            basisGrams = 100.0,
            rawJson = offProduct(
                """{"sodium_100g":0.05,"sodium_serving":0.9,"vitamin-c_100g":1.0}""",
            ),
        )
        val p = OffMicrosBackfill.profileFromSnapshot(snap)!!
        assertEquals(50.0, p[NutrientId.SODIUM]!!, 0.01)
        assertTrue(p.hasAny)
    }
}
