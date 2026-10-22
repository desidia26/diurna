package app.diurna.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeLookupTest {
    @Test fun upcAAndEan13OfTheSameProductMatch() {
        assertEquals("0012345678905", normalizeBarcode("012345678905"))
        assertEquals("0012345678905", normalizeBarcode("0012345678905"))
        assertEquals("96385074", normalizeBarcode("96385074"))
    }

    @Test fun notAProductBarcode() {
        assertNull(normalizeBarcode("https://example.com"))
        assertNull(normalizeBarcode("12345"))
        assertNull(normalizeBarcode(""))
    }

    @Test fun upcEExpandsToUpcA() {
        // One per expansion rule, by the last of the six middle digits: 0-2, 3, 4, 5-9.
        assertEquals("042100005264", expandUpcE("04252614"))
        assertEquals("012300000453", expandUpcE("01234533"))
        assertEquals("012340000057", expandUpcE("01234547"))
        assertEquals("012345000058", expandUpcE("01234558"))
        assertEquals("0042100005264", normalizeBarcode("04252614", upcE = true))
    }

    private fun off(product: String) = """{"status":1,"product":$product}"""

    @Test fun perServingNumbersWin() {
        val m = parseOffProduct(off("""{"product_name":"Protein shake","brands":"Premier Protein, Inc","serving_size":"1 bottle (325 ml)",
            "nutriments":{"energy-kcal_serving":160,"proteins_serving":30,"carbohydrates_serving":5,"fat_serving":3,"energy-kcal_100g":49}}"""))!!
        assertEquals("Protein shake (Premier Protein)", m.name)
        assertEquals(160, m.kcal)
        assertEquals(30, m.proteinG)
        assertEquals("1 bottle (325 ml)", m.note)
    }

    @Test fun per100gScaledToTheServing() {
        val m = parseOffProduct(off("""{"product_name":"Granola","serving_size":"45 g","serving_quantity":"45",
            "nutriments":{"energy-kcal_100g":450,"proteins_100g":10,"carbohydrates_100g":60,"fat_100g":18}}"""))!!
        assertEquals(203, m.kcal) // 202.5
        assertEquals(5, m.proteinG) // 4.5
        assertEquals(27, m.carbsG)
        assertEquals("45 g", m.note)
    }

    @Test fun per100gWhenThereIsNoServing() {
        val m = parseOffProduct(off("""{"product_name":"Oats","nutriments":{"energy-kcal_100g":379,"proteins_100g":13}}"""))!!
        assertEquals(379, m.kcal)
        assertEquals("100 g", m.note)
    }

    @Test fun kilojoulesOnly() {
        val m = parseOffProduct(off("""{"product_name":"Biscuit","nutriments":{"energy_100g":2092}}"""))!!
        assertEquals(500, m.kcal)
    }

    @Test fun brandAlreadyInTheNameIsntRepeated() {
        assertEquals("Quest bar", parseOffProduct(off("""{"product_name":"Quest bar","brands":"Quest","nutriments":{"energy-kcal_100g":350}}"""))!!.name)
    }

    @Test fun noCaloriesOrNoProductIsNotFound() {
        assertNull(parseOffProduct(off("""{"product_name":"Water","nutriments":{}}""")))
        assertNull(parseOffProduct("""{"status":0,"status_verbose":"product not found"}"""))
    }

    // ---- Hostile or vandalized answers: anyone can edit Open Food Facts ----

    @Test fun malformedJsonIsNotFoundNotACrash() {
        assertNull(parseOffProduct("<html>gateway error</html>"))
        assertNull(parseOffProduct(""))
        assertNull(parseOffProduct("""{"status":1,"product":"surprise"}"""))
        assertNull(parseOffProduct("""{"status":1,"product":{"nutriments":[1,2,3]}}"""))
    }

    @Test fun nonsenseNumbersAreRejected() {
        fun kcal100(v: String) = parseOffProduct(off("""{"product_name":"X","nutriments":{"energy-kcal_100g":$v}}"""))
        assertNull("NaN", kcal100("\"NaN\""))
        assertNull("Infinity", kcal100("\"Infinity\""))
        assertNull("negative", kcal100("-200"))
        assertNull("absurd", kcal100("1e300"))
        assertNull("too much for one serving", kcal100("9000"))
        assertNull("not a number", kcal100("\"lots\""))
    }

    @Test fun nonsenseMacrosAndServingsAreContained() {
        val m = parseOffProduct(off("""{"product_name":"X","serving_quantity":"-5",
            "nutriments":{"energy-kcal_100g":100,"proteins_100g":-3,"carbohydrates_100g":"NaN","fat_100g":1e12}}"""))!!
        assertEquals("negative serving weight ignored: per 100 g", "100 g", m.note)
        assertEquals(0, m.proteinG)
        assertEquals(0, m.carbsG)
        assertEquals("capped", 1000, m.fatG)
    }

    @Test fun textIsOneShortPlainLine() {
        val long = "A".repeat(5000)
        val m = parseOffProduct(off("""{"product_name":"Evil\nline\u0000two\u202Eflip $long","brands":"B",
            "serving_size":"1\tcup\r\n$long","nutriments":{"energy-kcal_serving":100}}"""))!!
        assertEquals(80, m.name.length)
        assertTrue(m.name.startsWith("Evil line twoflip AAA"))
        assertTrue(m.name.none { it.isISOControl() || it == '\u202E' })
        assertTrue(m.note!!.startsWith("1 cup AAA"))
        assertEquals(60, m.note!!.length)
    }
}
