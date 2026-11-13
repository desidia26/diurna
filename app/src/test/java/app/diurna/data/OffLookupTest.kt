package app.diurna.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Open Food Facts lookups say why they missed, and try a UPC-A's 12-digit spelling too. */
class OffLookupTest {
    private val full = """{"status":1,"product":{"product_name":"Pecan halves","brands":"Member's Mark","serving_size":"1 oz (28 g)",
        "serving_quantity":28,"nutriments":{"energy-kcal_100g":714,"proteins_100g":10.7,"carbohydrates_100g":3.6,"fat_100g":71.4}}}"""
    private val noNutriments = """{"status":1,"product":{"product_name":"Mystery snack","brands":"Acme","ingredients_text":"Oats, honey"}}"""
    private val noCalories = """{"status":1,"product":{"product_name":"Tea","nutriments":{"proteins_100g":0}}}"""
    private val implausible = """{"status":1,"product":{"product_name":"Typo","nutriments":{"energy-kcal_serving":99999}}}"""
    private val statusZero = """{"status":0,"status_verbose":"product not found"}"""

    private class Fake(val answers: Map<String, String?>, val unreachable: Set<String> = emptySet()) : ProductSource {
        val asked = mutableListOf<String>()
        override suspend fun fetch(code: String): String? {
            asked += code
            if (code in unreachable) throw IOException("down")
            return answers[code]
        }
    }

    @Test fun upcAHasTwoSpellingsEan8AndOtherEan13HaveOne() {
        assertEquals(listOf("0839425000889", "839425000889"), barcodeSpellings("0839425000889"))
        assertEquals(listOf("4006381333931"), barcodeSpellings("4006381333931"))
        assertEquals(listOf("96385074"), barcodeSpellings("96385074"))
    }

    @Test fun foundUnderTheEan13SpellingAsksOnce() = runBlocking {
        val src = Fake(mapOf("0839425000889" to full))
        val r = lookupOff("0839425000889", src) as OffLookup.Found
        assertEquals("0839425000889", r.code)
        assertEquals(listOf("0839425000889"), src.asked)
    }

    @Test fun missingEan13FallsBackToTheUpcASpelling() = runBlocking {
        val src = Fake(mapOf("0839425000889" to null, "839425000889" to full))
        val r = lookupOff("0839425000889", src) as OffLookup.Found
        assertEquals("839425000889", r.code)
        assertEquals(listOf("0839425000889", "839425000889"), src.asked)
    }

    @Test fun statusZeroCountsAsMissingAndTriesTheOtherSpelling() = runBlocking {
        val src = Fake(mapOf("0839425000889" to statusZero, "839425000889" to full))
        assertTrue(lookupOff("0839425000889", src) is OffLookup.Found)
    }

    @Test fun missingUnderBothSpellingsIsNoProduct() = runBlocking {
        val src = Fake(mapOf("0839425000889" to null, "839425000889" to statusZero))
        val r = lookupOff("0839425000889", src) as OffLookup.NoProduct
        assertEquals(listOf("0839425000889", "839425000889"), r.tried)
    }

    @Test fun productWithoutNutritionIsIncompleteNotMissing() = runBlocking {
        val r = lookupOff("0012345678905", Fake(mapOf("0012345678905" to noNutriments))) as OffLookup.Incomplete
        assertEquals(OffProblem.NO_NUTRITION, r.problem)
        assertEquals("Mystery snack (Acme)", offProductName(r.json))
        assertEquals(OffProblem.NO_CALORIES,
            (lookupOff("0012345678905", Fake(mapOf("0012345678905" to noCalories))) as OffLookup.Incomplete).problem)
        assertEquals(OffProblem.IMPLAUSIBLE,
            (lookupOff("0012345678905", Fake(mapOf("0012345678905" to implausible))) as OffLookup.Incomplete).problem)
    }

    @Test fun incompleteUnderOneSpellingStillPrefersAFullRecordUnderTheOther() = runBlocking {
        val src = Fake(mapOf("0839425000889" to noNutriments, "839425000889" to full))
        assertEquals("839425000889", (lookupOff("0839425000889", src) as OffLookup.Found).code)
    }

    @Test fun garbageAnswerIsUnreadableNotMissing() = runBlocking {
        val r = lookupOff("0012345678905", Fake(mapOf("0012345678905" to "<html>oops</html>", "012345678905" to null)))
        assertTrue(r is OffLookup.Unreadable)
    }

    @Test fun unreachableThrowsSoTheCallerCanSayOffline() = runBlocking {
        try {
            lookupOff("0012345678905", Fake(emptyMap(), unreachable = setOf("0012345678905")))
            fail("expected IOException")
        } catch (_: IOException) { }
    }

    @Test fun problemWiresAreStableForDebugLogs() {
        assertEquals(listOf("no_nutrition", "no_calories", "implausible"), OffProblem.entries.map { it.wire })
    }
}
