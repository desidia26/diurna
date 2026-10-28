package app.diurna.ai

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test fun stripsControlCharactersAndCapsTheNote() {
        assertEquals("hithere", EstimatePrompt.sanitizeNote("hi\u0000there\n"))
        assertEquals(EstimatePrompt.NOTE_MAX, EstimatePrompt.sanitizeNote("a".repeat(200)).length)

        val rawNote = "hi\u0000there\n" + "a".repeat(200)
        val json = ObjectMapper().writeValueAsString(
            mapOf(
                "kcal_low" to 1,
                "kcal_high" to 2,
                "protein_g" to 0,
                "carbs_g" to 0,
                "fat_g" to 0,
                "note" to rawNote,
            ),
        )
        val e = EstimatePrompt.parse(json)
        assertEquals("hithere" + "a".repeat(EstimatePrompt.NOTE_MAX - 7), e.note)
        assertEquals(EstimatePrompt.NOTE_MAX, e.note.length)
        assertTrue(e.note.none { it.isISOControl() })
    }
}
