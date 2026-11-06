package app.diurna.data.fdc

import app.diurna.domain.NutrientId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FdcMicronutrientsTest {

    private fun food(nutrientsJson: String) = JSONObject(
        """{"fdcId":1,"description":"Test","foodNutrients":$nutrientsJson}""",
    )

    @Test fun parsesMineralsAndVitaminsInCanonicalUnits() {
        val food = food(
            """
            [
              {"nutrient":{"id":1093,"name":"Sodium, Na","unitName":"mg"},"amount":5.0},
              {"nutrient":{"id":1092,"name":"Potassium, K","unitName":"mg"},"amount":200.0},
              {"nutrient":{"id":1087,"name":"Calcium, Ca","unitName":"mg"},"amount":50.0},
              {"nutrient":{"id":1089,"name":"Iron, Fe","unitName":"mg"},"amount":1.5},
              {"nutrient":{"id":1090,"name":"Magnesium, Mg","unitName":"mg"},"amount":30.0},
              {"nutrient":{"id":1095,"name":"Zinc, Zn","unitName":"mg"},"amount":0.8},
              {"nutrient":{"id":1106,"name":"Vitamin A, RAE","unitName":"µg"},"amount":42.0},
              {"nutrient":{"id":1162,"name":"Vitamin C, total ascorbic acid","unitName":"mg"},"amount":12.0},
              {"nutrient":{"id":1114,"name":"Vitamin D (D2 + D3)","unitName":"µg"},"amount":2.5},
              {"nutrient":{"id":1178,"name":"Vitamin B-12","unitName":"µg"},"amount":0.4},
              {"nutrient":{"id":1177,"name":"Folate, DFE","unitName":"µg"},"amount":80.0},
              {"nutrient":{"id":1003,"name":"Protein","unitName":"g"},"amount":9.0}
            ]
            """.trimIndent(),
        )
        val p = FdcClient.parseMicronutrients(food)!!
        assertEquals(5.0, p[NutrientId.SODIUM]!!, 0.0)
        assertEquals(200.0, p[NutrientId.POTASSIUM]!!, 0.0)
        assertEquals(50.0, p[NutrientId.CALCIUM]!!, 0.0)
        assertEquals(1.5, p[NutrientId.IRON]!!, 0.0)
        assertEquals(30.0, p[NutrientId.MAGNESIUM]!!, 0.0)
        assertEquals(0.8, p[NutrientId.ZINC]!!, 0.0)
        assertEquals(42.0, p[NutrientId.VITAMIN_A_RAE]!!, 0.0)
        assertEquals(12.0, p[NutrientId.VITAMIN_C]!!, 0.0)
        assertEquals(2.5, p[NutrientId.VITAMIN_D]!!, 0.0)
        assertEquals(0.4, p[NutrientId.VITAMIN_B12]!!, 0.0)
        assertEquals(80.0, p[NutrientId.FOLATE_DFE]!!, 0.0)
    }

    @Test fun missingNutrientStaysAbsentNotZero() {
        val food = food(
            """[{"nutrient":{"id":1093},"amount":10.0},{"nutrient":{"id":1008},"amount":100.0}]""",
        )
        val p = FdcClient.parseMicronutrients(food)!!
        assertEquals(10.0, p[NutrientId.SODIUM]!!, 0.0)
        assertNull(p[NutrientId.IRON])
        assertNull(p[NutrientId.VITAMIN_A_RAE])
    }

    @Test fun knownZeroIsKept() {
        val food = food("""[{"nutrient":{"id":1093},"amount":0.0}]""")
        val p = FdcClient.parseMicronutrients(food)!!
        assertEquals(0.0, p[NutrientId.SODIUM]!!, 0.0)
    }

    @Test fun rejectsNonFiniteAndNegativeAmounts() {
        val food = food(
            """
            [
              {"nutrient":{"id":1093},"amount":"NaN"},
              {"nutrient":{"id":1089},"amount":-1},
              {"nutrient":{"id":1090},"amount":null},
              {"nutrient":{"id":1092},"amount":40}
            ]
            """.trimIndent(),
        )
        // optDouble on bad values → NaN → rejected; only potassium kept
        val p = FdcClient.parseMicronutrients(food)!!
        assertNull(p[NutrientId.SODIUM])
        assertNull(p[NutrientId.IRON])
        assertEquals(40.0, p[NutrientId.POTASSIUM]!!, 0.0)
    }

    @Test fun vitaminAIuAloneDoesNotBecomeRae() {
        val food = food(
            """[{"nutrient":{"id":1104,"name":"Vitamin A, IU"},"amount":500.0}]""",
        )
        // IU→RAE depends on retinol vs carotenoids; never invent RAE from IU.
        assertNull(FdcClient.parseMicronutrients(food))
    }

    @Test fun vitaminARaePreferredWhenBothPresent() {
        val food = food(
            """
            [
              {"nutrient":{"id":1104},"amount":500.0},
              {"nutrient":{"id":1106},"amount":15.0}
            ]
            """.trimIndent(),
        )
        val p = FdcClient.parseMicronutrients(food)!!
        assertEquals(15.0, p[NutrientId.VITAMIN_A_RAE]!!, 0.0)
    }

    @Test fun vitaminDIuConvertsWhenUgMissing() {
        val food = food(
            """[{"nutrient":{"id":1110,"name":"Vitamin D","unitName":"IU"},"amount":400.0}]""",
        )
        val p = FdcClient.parseMicronutrients(food)!!
        assertEquals(10.0, p[NutrientId.VITAMIN_D]!!, 1e-9) // 400 × 0.025
    }

    @Test fun vitaminDUgPreferredOverIu() {
        val food = food(
            """
            [
              {"nutrient":{"id":1110},"amount":400.0},
              {"nutrient":{"id":1114},"amount":3.0}
            ]
            """.trimIndent(),
        )
        val p = FdcClient.parseMicronutrients(food)!!
        assertEquals(3.0, p[NutrientId.VITAMIN_D]!!, 0.0)
    }

    @Test fun folateTotalWithoutDfeIsIgnored() {
        // Food folate / folic acid need DFE math; only nutrient 1177 is canonical.
        val food = food(
            """
            [
              {"nutrient":{"id":1176,"name":"Folate, total"},"amount":50.0},
              {"nutrient":{"id":1186,"name":"Folic acid"},"amount":20.0}
            ]
            """.trimIndent(),
        )
        assertNull(FdcClient.parseMicronutrients(food))
    }

    @Test fun duplicateNutrientIdKeepsLastValid() {
        val food = food(
            """
            [
              {"nutrient":{"id":1093},"amount":1.0},
              {"nutrient":{"id":1093},"amount":9.0}
            ]
            """.trimIndent(),
        )
        assertEquals(9.0, FdcClient.parseMicronutrients(food)!![NutrientId.SODIUM]!!, 0.0)
    }

    @Test fun searchHitNutrientIdFieldAlsoWorks() {
        val food = food("""[{"nutrientId":1093,"amount":7.0}]""")
        assertEquals(7.0, FdcClient.parseMicronutrients(food)!![NutrientId.SODIUM]!!, 0.0)
    }

    @Test fun emptyOrNoMicrosReturnsNull() {
        assertNull(FdcClient.parseMicronutrients(food("[]")))
        assertNull(
            FdcClient.parseMicronutrients(
                food("""[{"nutrient":{"id":1008},"amount":100}]"""),
            ),
        )
        assertNull(FdcClient.parseMicronutrients(JSONObject("""{"fdcId":1}""")))
    }

    @Test fun nutritionProfileStillParsesMacrosUnchanged() {
        // Micronutrient work must not break existing macro parse.
        val food = food(
            """
            [
              {"nutrient":{"id":1008},"amount":150},
              {"nutrient":{"id":1003},"amount":10},
              {"nutrient":{"id":1005},"amount":20},
              {"nutrient":{"id":1004},"amount":5},
              {"nutrient":{"id":1093},"amount":80}
            ]
            """.trimIndent(),
        )
        val macros = FdcClient.parseNutritionProfile(food)!!
        assertEquals(150, macros.kcalPer100)
        assertEquals(10, macros.per100.proteinG)
        // Micros available separately; profile type stays macro-focused in this PR.
        val micros = FdcClient.parseMicronutrients(food)!!
        assertEquals(80.0, micros[NutrientId.SODIUM]!!, 0.0)
    }
}
