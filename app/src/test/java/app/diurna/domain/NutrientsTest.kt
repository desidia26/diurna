package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutrientsTest {

    @Test fun registryCoversFirstElevenNutrientsWithCanonicalUnits() {
        val ids = NutrientId.entries.map { it.id }.toSet()
        assertEquals(
            setOf(
                "sodium", "potassium", "calcium", "iron", "magnesium", "zinc",
                "vitamin_a_rae", "vitamin_c", "vitamin_d", "vitamin_b12", "folate_dfe",
            ),
            ids,
        )
        assertEquals(NutrientUnit.MG, NutrientId.SODIUM.unit)
        assertEquals(NutrientUnit.MG, NutrientId.POTASSIUM.unit)
        assertEquals(NutrientUnit.MG, NutrientId.CALCIUM.unit)
        assertEquals(NutrientUnit.MG, NutrientId.IRON.unit)
        assertEquals(NutrientUnit.MG, NutrientId.MAGNESIUM.unit)
        assertEquals(NutrientUnit.MG, NutrientId.ZINC.unit)
        assertEquals(NutrientUnit.MG, NutrientId.VITAMIN_C.unit)
        assertEquals(NutrientUnit.UG, NutrientId.VITAMIN_A_RAE.unit)
        assertEquals(NutrientUnit.UG, NutrientId.VITAMIN_D.unit)
        assertEquals(NutrientUnit.UG, NutrientId.VITAMIN_B12.unit)
        assertEquals(NutrientUnit.UG, NutrientId.FOLATE_DFE.unit)
    }

    @Test fun fdcIdsPreferRaeAndDfeNeverIuAloneForVitaminA() {
        val a = NutrientRegistry.def(NutrientId.VITAMIN_A_RAE)
        assertTrue(a.fdcIds.contains(1106)) // Vitamin A, RAE
        assertFalse(a.fdcIds.contains(1104)) // Vitamin A, IU — form-dependent; never auto-convert
        val folate = NutrientRegistry.def(NutrientId.FOLATE_DFE)
        assertTrue(folate.fdcIds.contains(1177)) // Folate, DFE
        val d = NutrientRegistry.def(NutrientId.VITAMIN_D)
        assertTrue(d.fdcIds.contains(1114)) // Vitamin D (D2 + D3), µg
    }

    @Test fun missingIsDistinctFromZero() {
        val empty = MicronutrientProfile()
        assertNull(empty[NutrientId.SODIUM])
        assertFalse(empty.hasAny)

        val zero = MicronutrientProfile.of(NutrientId.SODIUM to 0.0)
        assertEquals(0.0, zero[NutrientId.SODIUM]!!, 0.0)
        assertTrue(zero.hasAny)
        assertNull(zero[NutrientId.IRON])
    }

    @Test fun rejectsNonFiniteAndNegativeOnConstruction() {
        assertNull(NutrientRegistry.sanitize(Double.NaN))
        assertNull(NutrientRegistry.sanitize(Double.POSITIVE_INFINITY))
        assertNull(NutrientRegistry.sanitize(Double.NEGATIVE_INFINITY))
        assertNull(NutrientRegistry.sanitize(-1.0))
        assertEquals(0.0, NutrientRegistry.sanitize(0.0)!!, 0.0)
        assertEquals(12.5, NutrientRegistry.sanitize(12.5)!!, 0.0)
    }

    @Test fun duplicateKeysKeepLastFiniteNonNegative() {
        val p = MicronutrientProfile.fromPairs(
            listOf(
                NutrientId.SODIUM to Double.NaN,
                NutrientId.SODIUM to 100.0,
                NutrientId.SODIUM to -5.0,
                NutrientId.SODIUM to 200.0,
            ),
        )
        assertEquals(200.0, p[NutrientId.SODIUM]!!, 0.0)
    }

    @Test fun scaleMultipliesKnownOnly() {
        val p = MicronutrientProfile.of(
            NutrientId.SODIUM to 100.0,
            NutrientId.IRON to 2.0,
        )
        val half = p.scale(0.5)
        assertEquals(50.0, half[NutrientId.SODIUM]!!, 0.0)
        assertEquals(1.0, half[NutrientId.IRON]!!, 0.0)
        assertNull(half[NutrientId.CALCIUM])
    }

    @Test fun sumKeepsNullWhenEveryItemUnknown() {
        val a = MicronutrientProfile.of(NutrientId.SODIUM to 10.0)
        val b = MicronutrientProfile.of(NutrientId.IRON to 1.0)
        val c = MicronutrientProfile()
        val sum = MicronutrientProfile.sum(listOf(a, b, c))
        assertEquals(10.0, sum[NutrientId.SODIUM]!!, 0.0)
        assertEquals(1.0, sum[NutrientId.IRON]!!, 0.0)
        assertNull(sum[NutrientId.CALCIUM])
    }

    @Test fun aggregateTracksCompletenessPerItem() {
        val with = MicronutrientProfile.of(NutrientId.SODIUM to 5.0)
        val empty = MicronutrientProfile()
        val agg = MicronutrientAggregate.of(listOf(with, empty, with, empty, empty, with))
        assertEquals(3, agg.contributing)
        assertEquals(6, agg.total)
        assertTrue(agg.partial)
        assertEquals("from 3 of 6 items", agg.completenessLabel)
        assertEquals(15.0, agg.profile[NutrientId.SODIUM]!!, 0.0)
    }

    @Test fun aggregateAllKnownIsNotPartial() {
        val a = MicronutrientProfile.of(NutrientId.CALCIUM to 1.0)
        val agg = MicronutrientAggregate.of(listOf(a, a))
        assertFalse(agg.partial)
        assertEquals("from 2 of 2 items", agg.completenessLabel)
    }

    @Test fun aggregateNoneKnown() {
        val agg = MicronutrientAggregate.of(listOf(MicronutrientProfile(), MicronutrientProfile()))
        assertEquals(0, agg.contributing)
        assertEquals(2, agg.total)
        assertFalse(agg.profile.hasAny)
        assertEquals("from 0 of 2 items", agg.completenessLabel)
    }

    @Test fun saltToSodiumFactorIsDocumentedPointFour() {
        // NaCl is ~40% sodium by mass; only used when sodium itself is absent.
        assertEquals(0.4, NutrientRegistry.SALT_TO_SODIUM, 0.0)
    }

    @Test fun vitaminDIuToUgFactor() {
        // 1 IU vitamin D = 0.025 µg; safe and form-independent.
        assertEquals(0.025, NutrientRegistry.VITAMIN_D_IU_TO_UG, 0.0)
        assertEquals(10.0, 400.0 * NutrientRegistry.VITAMIN_D_IU_TO_UG, 0.0)
    }
}
