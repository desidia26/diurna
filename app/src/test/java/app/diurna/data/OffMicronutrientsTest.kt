package app.diurna.data

import app.diurna.domain.NutrientId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Open Food Facts stores many minerals in grams for the plain key (sodium_100g is g).
 * Vitamins often use the named unit in the value; IU keys are handled explicitly.
 */
class OffMicronutrientsTest {

    private fun nutriments(body: String) = JSONObject(body)

    @Test fun parsesMineralsFromGramsToMg() {
        // OFF sodium_100g / potassium_100g / … are grams → mg ×1000.
        val n = nutriments(
            """
            {
              "sodium_100g": 0.5,
              "potassium_100g": 0.2,
              "calcium_100g": 0.05,
              "iron_100g": 0.0015,
              "magnesium_100g": 0.03,
              "zinc_100g": 0.0008
            }
            """.trimIndent(),
        )
        val p = parseOffMicronutrients(n, "_100g")!!
        assertEquals(500.0, p[NutrientId.SODIUM]!!, 0.01)
        assertEquals(200.0, p[NutrientId.POTASSIUM]!!, 0.01)
        assertEquals(50.0, p[NutrientId.CALCIUM]!!, 0.01)
        assertEquals(1.5, p[NutrientId.IRON]!!, 0.01)
        assertEquals(30.0, p[NutrientId.MAGNESIUM]!!, 0.01)
        assertEquals(0.8, p[NutrientId.ZINC]!!, 0.01)
    }

    @Test fun prefersExplicitMgKeysWhenPresent() {
        val n = nutriments(
            """{"sodium_100g": 0.5, "sodium-mg_100g": 123.0}""",
        )
        val p = parseOffMicronutrients(n, "_100g")!!
        assertEquals(123.0, p[NutrientId.SODIUM]!!, 0.0)
    }

    @Test fun saltConvertsToSodiumOnlyWhenSodiumAbsent() {
        // salt_100g is NaCl grams; sodium mass ≈ 0.4 × salt.
        val fromSalt = parseOffMicronutrients(
            nutriments("""{"salt_100g": 1.0}"""),
            "_100g",
        )!!
        assertEquals(400.0, fromSalt[NutrientId.SODIUM]!!, 0.01)

        val sodiumWins = parseOffMicronutrients(
            nutriments("""{"salt_100g": 1.0, "sodium_100g": 0.1}"""),
            "_100g",
        )!!
        assertEquals(100.0, sodiumWins[NutrientId.SODIUM]!!, 0.01)
    }

    @Test fun vitaminsPreferCanonicalForms() {
        val n = nutriments(
            """
            {
              "vitamin-a_100g": 42.0,
              "vitamin-c_100g": 12.0,
              "vitamin-d_100g": 2.5,
              "vitamin-b12_100g": 0.4,
              "folate-equivalent_100g": 80.0
            }
            """.trimIndent(),
        )
        val p = parseOffMicronutrients(n, "_100g")!!
        assertEquals(42.0, p[NutrientId.VITAMIN_A_RAE]!!, 0.0)
        assertEquals(12.0, p[NutrientId.VITAMIN_C]!!, 0.0)
        assertEquals(2.5, p[NutrientId.VITAMIN_D]!!, 0.0)
        assertEquals(0.4, p[NutrientId.VITAMIN_B12]!!, 0.0)
        assertEquals(80.0, p[NutrientId.FOLATE_DFE]!!, 0.0)
    }

    @Test fun vitaminAIuAloneDoesNotBecomeRae() {
        assertNull(
            parseOffMicronutrients(
                nutriments("""{"vitamin-a-iu_100g": 500.0}"""),
                "_100g",
            ),
        )
    }

    @Test fun vitaminDIuConvertsWhenUgMissing() {
        val p = parseOffMicronutrients(
            nutriments("""{"vitamin-d-iu_100g": 400.0}"""),
            "_100g",
        )!!
        assertEquals(10.0, p[NutrientId.VITAMIN_D]!!, 1e-9)
    }

    @Test fun missingStaysAbsentKnownZeroKept() {
        val missing = parseOffMicronutrients(
            nutriments("""{"sodium_100g": 0.1}"""),
            "_100g",
        )!!
        assertEquals(100.0, missing[NutrientId.SODIUM]!!, 0.01)
        assertNull(missing[NutrientId.IRON])

        val zero = parseOffMicronutrients(
            nutriments("""{"iron_100g": 0.0}"""),
            "_100g",
        )!!
        assertEquals(0.0, zero[NutrientId.IRON]!!, 0.0)
    }

    @Test fun rejectsNonFiniteNegativeAndHuge() {
        val p = parseOffMicronutrients(
            nutriments(
                """
                {
                  "sodium_100g": -1,
                  "potassium_100g": "NaN",
                  "calcium_100g": 1e9,
                  "iron_100g": 0.002
                }
                """.trimIndent(),
            ),
            "_100g",
        )!!
        assertNull(p[NutrientId.SODIUM])
        assertNull(p[NutrientId.POTASSIUM])
        assertNull(p[NutrientId.CALCIUM])
        assertEquals(2.0, p[NutrientId.IRON]!!, 0.01)
    }

    @Test fun servingSuffixScalesIndependentlyOfEnergy() {
        // Parser is pure over nutriments+suffix; callers choose _serving vs _100g.
        val p = parseOffMicronutrients(
            nutriments("""{"sodium_serving": 0.2, "vitamin-c_serving": 30.0}"""),
            "_serving",
        )!!
        assertEquals(200.0, p[NutrientId.SODIUM]!!, 0.01)
        assertEquals(30.0, p[NutrientId.VITAMIN_C]!!, 0.0)
    }

    @Test fun emptyReturnsNull() {
        assertNull(parseOffMicronutrients(nutriments("""{}"""), "_100g"))
        assertNull(
            parseOffMicronutrients(
                nutriments("""{"energy-kcal_100g": 100}"""),
                "_100g",
            ),
        )
    }

    @Test fun folateWithoutEquivalentIgnored() {
        assertNull(
            parseOffMicronutrients(
                nutriments("""{"folate_100g": 50.0, "folic-acid_100g": 20.0}"""),
                "_100g",
            ),
        )
    }
}
