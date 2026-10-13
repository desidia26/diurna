package app.daybook.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EstimateParseTest {
    @Test fun parsesAWellFormedReply() {
        val e = EstimatePrompt.parse("""{"kcal_low":650,"kcal_high":950,"protein_g":45,"carbs_g":80,"fat_g":28,"note":"1 cup rice, no guac"}""")
        assertEquals(FoodEstimate(650, 950, 45, 80, 28, "1 cup rice, no guac"), e)
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
}
