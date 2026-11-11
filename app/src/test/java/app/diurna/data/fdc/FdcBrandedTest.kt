package app.diurna.data.fdc

import app.diurna.domain.NutrientId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** USDA Branded Foods: exact GTIN/UPC matches and a name search, per-100 g, missing stays unknown. */
class FdcBrandedTest {
    // Shape of /fdc/v1/foods/search hits for dataType=Branded (values per 100 g; synthetic numbers).
    private fun hit(fdcId: Int, gtin: String, desc: String = "SLICED ALMONDS", extra: String = "") = """
        {"fdcId":$fdcId,"description":"$desc","dataType":"Branded","gtinUpc":"$gtin",
         "brandOwner":"Mariani Nut Company","brandName":"MARIANI","ingredients":"ALMONDS.",
         "servingSize":30.0,"servingSizeUnit":"g","householdServingFullText":"1/4 cup",
         "foodNutrients":[
           {"nutrientId":1008,"nutrientName":"Energy","unitName":"KCAL","value":567},
           {"nutrientId":1003,"nutrientName":"Protein","unitName":"G","value":20.0},
           {"nutrientId":1005,"nutrientName":"Carbohydrate, by difference","unitName":"G","value":20.0},
           {"nutrientId":1004,"nutrientName":"Total lipid (fat)","unitName":"G","value":50.0},
           {"nutrientId":1079,"nutrientName":"Fiber","unitName":"G","value":13.3},
           {"nutrientId":1093,"nutrientName":"Sodium, Na","unitName":"MG","value":0.0},
           {"nutrientId":1087,"nutrientName":"Calcium, Ca","unitName":"MG","value":267}
           $extra
         ]}"""

    private class Fake(val answer: (String) -> String?) : FdcTransport {
        val paths = mutableListOf<String>()
        override fun get(path: String, apiKey: String): String? { paths += path; return answer(path) }
    }

    private fun search(vararg hits: String) = """{"totalHits":${hits.size},"foods":[${hits.joinToString(",")}]}"""

    @Test fun parsesPer100gServingAndProvenanceFields() {
        val f = FdcClient.parseBranded(org.json.JSONObject(hit(123, "076991000411")))!!
        assertEquals(123, f.fdcId)
        assertEquals("0076991000411", f.gtin)
        assertEquals("MARIANI", f.brand)
        assertEquals("ALMONDS.", f.ingredients)
        assertEquals(30.0, f.servingGrams!!, 1e-9)
        assertEquals("1/4 cup", f.servingText)
        assertEquals(567.0, f.per100.getValue("kcal"), 1e-9)
        assertEquals(50.0, f.per100.getValue("fat_g"), 1e-9)
        assertNull("not on this record: unknown, not zero", f.per100["sugars_g"])
        assertEquals(0.0, f.micros!!.asMap().getValue(NutrientId.SODIUM), 1e-9)
        assertEquals(267.0, f.micros!!.asMap().getValue(NutrientId.CALCIUM), 1e-9)
    }

    @Test fun volumeServingHasNoGramWeight() {
        val f = FdcClient.parseBranded(org.json.JSONObject(hit(1, "012345678905").replace("\"servingSizeUnit\":\"g\"", "\"servingSizeUnit\":\"ml\"")))!!
        assertNull(f.servingGrams)
    }

    @Test fun implausibleEnergyIsRejected() {
        val bad = hit(1, "012345678905").replace("\"value\":567", "\"value\":5000")
        assertNull(FdcClient.parseBranded(org.json.JSONObject(bad)))
    }

    @Test fun gtinLookupOnlyAcceptsAnExactCodeMatch() = runBlocking {
        val t = Fake { search(hit(9, "0000000000017", "WRONG"), hit(123, "00076991000411")) }
        val f = FdcClient(apiKey = { "k" }, transport = t).brandedByGtin("0076991000411")!!
        assertEquals(123, f.fdcId) // GTIN-14 with leading zeros normalizes to the same product
        assertTrue(t.paths.single().contains("dataType=Branded"))
        assertTrue(t.paths.single().contains("query=076991000411"))
    }

    @Test fun gtinLookupTriesTheThirteenDigitSpellingSecond() = runBlocking {
        val t = Fake { p -> if (p.contains("query=0076991000411")) search(hit(5, "0076991000411")) else search() }
        assertEquals(5, FdcClient(apiKey = { "k" }, transport = t).brandedByGtin("0076991000411")!!.fdcId)
        assertEquals(2, t.paths.size)
    }

    @Test fun noMatchingGtinIsNull() = runBlocking {
        val t = Fake { search(hit(9, "0000000000017")) }
        assertNull(FdcClient(apiKey = { "k" }, transport = t).brandedByGtin("0076991000411"))
    }

    @Test fun withoutAKeyNothingIsSent() = runBlocking {
        val t = Fake { error("no request expected") }
        val c = FdcClient(apiKey = { null }, transport = t)
        assertNull(c.brandedByGtin("0076991000411"))
        assertTrue(c.brandedSearch("sliced almonds").isEmpty())
        assertTrue(t.paths.isEmpty())
    }

    @Test fun nameSearchReturnsBrandedHits() = runBlocking {
        val t = Fake { search(hit(1, "076991000411"), hit(2, "012345678905", "ALMONDS, SLICED")) }
        val hits = FdcClient(apiKey = { "k" }, transport = t).brandedSearch("Mariani sliced almonds")
        assertEquals(listOf(1, 2), hits.map { it.fdcId })
        assertTrue(t.paths.single().contains("query=Mariani+sliced+almonds"))
    }

    @Test fun nonDigitCodesNeverReachTheNetwork() = runBlocking {
        val t = Fake { error("no request expected") }
        assertNull(FdcClient(apiKey = { "k" }, transport = t).brandedByGtin("12345678;rm"))
    }

    @Test fun garbageAnswersAreNull() = runBlocking {
        val t = Fake { "<html>" }
        assertNull(FdcClient(apiKey = { "k" }, transport = t).brandedByGtin("0076991000411"))
        assertTrue(FdcClient(apiKey = { "k" }, transport = t).brandedSearch("x").isEmpty())
    }
}
