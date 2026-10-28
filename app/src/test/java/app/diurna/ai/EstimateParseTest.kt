package app.diurna.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EstimateParseTest {
    @Test fun parsesAWellFormedReply() {
        val e = EstimatePrompt.parse("""{"kcal_low":650,"kcal_high":950,"protein_g":45,"carbs_g":80,"fat_g":28,"note":"1 cup rice, no guac"}""")
        assertEquals(FoodEstimate(650, 950, 45, 80, 28, "1 cup rice, no guac"), e)
        assertNull(e.fiberG)
        assertNull(e.sugarsG)
        assertNull(e.satFatG)
    }

    @Test fun swapsAReversedRangeAndClampsNegatives() {
        val e = EstimatePrompt.parse("""{"kcal_low":900,"kcal_high":600,"protein_g":-3,"carbs_g":10,"fat_g":5,"note":""}""")
        assertEquals(600, e.kcalLow)
        assertEquals(900, e.kcalHigh)
        assertEquals(0, e.proteinG)
    }

    @Test fun missingFieldOrGarbageIsAPermanentFailure() {
        assertThrows(EstimateFailed::class.java) { EstimatePrompt.parse("""{"kcal_low":1}""") }
        assertThrows(EstimateFailed::class.java) { EstimatePrompt.parse("not json") }
    }

    @Test fun parsesDetailedFieldsIncludingNullAndZero() {
        val e = EstimatePrompt.parse(
            """{"kcal_low":200,"kcal_high":300,"protein_g":10,"carbs_g":40,"fat_g":8,"note":"oats",
               "fiber_g":5,"soluble_fiber_g":2,"insoluble_fiber_g":3,
               "sugars_g":1,"added_sugars_g":0,"starch_g":30,
               "sat_fat_g":1,"mono_fat_g":2,"poly_fat_g":3,"trans_fat_g":0,
               "cholesterol_mg":null,"omega3_g":null,"omega6_g":1,"omega9_g":2}"""
        )
        assertEquals(5, e.fiberG)
        assertEquals(2, e.solubleFiberG)
        assertEquals(3, e.insolubleFiberG)
        assertEquals(1, e.sugarsG)
        assertEquals(0, e.addedSugarsG) // explicit 0 is kept
        assertEquals(30, e.starchG)
        assertEquals(1, e.satFatG)
        assertEquals(2, e.monoFatG)
        assertEquals(3, e.polyFatG)
        assertEquals(0, e.transFatG)
        assertNull(e.cholesterolMg) // JSON null → unknown
        assertNull(e.omega3G)
        assertEquals(1, e.omega6G)
        assertEquals(2, e.omega9G)
    }

    @Test fun absentDetailedFieldsStayNull() {
        val e = EstimatePrompt.parse("""{"kcal_low":1,"kcal_high":2,"protein_g":0,"carbs_g":0,"fat_g":0,"note":"x"}""")
        assertNull(e.fiberG)
        assertNull(e.solubleFiberG)
        assertNull(e.insolubleFiberG)
        assertNull(e.sugarsG)
        assertNull(e.addedSugarsG)
        assertNull(e.starchG)
        assertNull(e.satFatG)
        assertNull(e.monoFatG)
        assertNull(e.polyFatG)
        assertNull(e.transFatG)
        assertNull(e.cholesterolMg)
        assertNull(e.omega3G)
        assertNull(e.omega6G)
        assertNull(e.omega9G)
    }

    @Test fun derivesFiberTotalFromSolublePlusInsoluble() {
        val e = EstimatePrompt.parse(
            """{"kcal_low":100,"kcal_high":150,"protein_g":5,"carbs_g":30,"fat_g":2,"note":"beans",
               "soluble_fiber_g":4,"insoluble_fiber_g":6}"""
        )
        assertEquals(10, e.fiberG)
        assertEquals(4, e.solubleFiberG)
        assertEquals(6, e.insolubleFiberG)
    }

    @Test fun clampsSubtypesToParentsAndNoNegatives() {
        val d = EstimatePrompt.clampDetail(
            carbsG = 20, fatG = 10,
            fiberG = 50, // > carbs
            solubleFiberG = -1,
            sugarsG = 30, // > carbs
            addedSugarsG = 40, // > sugars after clamp
            starchG = -5,
            satFatG = 20, // > fat
            cholesterolMg = -10,
            omega3G = 99,
        )
        assertEquals(20, d.fiberG)
        assertEquals(0, d.solubleFiberG)
        assertEquals(20, d.sugarsG)
        assertEquals(20, d.addedSugarsG)
        assertEquals(0, d.starchG)
        assertEquals(10, d.satFatG)
        assertEquals(0, d.cholesterolMg)
        assertEquals(10, d.omega3G)
    }

    @Test fun scalesFatSubtypesWhenTheirSumExceedsFat() {
        val d = EstimatePrompt.clampDetail(
            carbsG = 0, fatG = 10,
            satFatG = 8, monoFatG = 8, polyFatG = 8, transFatG = 8, // sum 32
        )
        assertEquals(10, (d.satFatG!! + d.monoFatG!! + d.polyFatG!! + d.transFatG!!))
        assertTrue(d.satFatG!! <= 10)
    }

    @Test fun scalesFiberSplitsWhenTheyExceedTotal() {
        val d = EstimatePrompt.clampDetail(
            carbsG = 50, fatG = 0,
            fiberG = 10, solubleFiberG = 9, insolubleFiberG = 9,
        )
        assertEquals(10, d.fiberG)
        assertEquals(10, d.solubleFiberG!! + d.insolubleFiberG!!)
    }

    @Test fun basicSchemaHasOnlySixRequiredFields() {
        val s = EstimatePrompt.schema(detailed = false)
        assertEquals(EstimatePrompt.SCHEMA, s)
        assertEquals(listOf("kcal_low", "kcal_high", "protein_g", "carbs_g", "fat_g", "note"), s["required"])
        val props = s["properties"] as Map<*, *>
        assertEquals(6, props.size)
        assertFalse(props.containsKey("fiber_g"))
    }

    @Test fun detailedSchemaAddsNullableSubMacrosAndMentionsThemInThePrompt() {
        val s = EstimatePrompt.schema(detailed = true)
        val props = s["properties"] as Map<*, *>
        assertTrue(props.containsKey("fiber_g"))
        assertTrue(props.containsKey("omega9_g"))
        assertTrue(props.containsKey("cholesterol_mg"))
        val required = s["required"] as List<*>
        assertTrue(required.contains("fiber_g"))
        assertTrue(required.size > 6)
        // Nullable type for strict schemas.
        val fiberType = (props["fiber_g"] as Map<*, *>)["type"] as List<*>
        assertEquals(listOf("integer", "null"), fiberType)

        val basic = EstimatePrompt.system(false)
        val detailed = EstimatePrompt.system(true)
        assertEquals(EstimatePrompt.SYSTEM, basic)
        assertFalse("fiber_g" in basic)
        assertTrue("fiber_g" in detailed)
        assertTrue("null" in detailed.lowercase())
        assertTrue("TOTAL carbs including fiber" in detailed || "total carbs including fiber" in detailed.lowercase())
        assertTrue(detailed.length - basic.length < 800) // keep the add-on small
    }
}
