package app.diurna.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test fun addsFiberBackIntoTotalCarbsUsStyle() {
        // OFF carbs are available (exclude fiber). We store total = available + fiber.
        val m = parseOffProduct(off("""{"product_name":"Beans","nutriments":{
            "energy-kcal_100g":100,"carbohydrates_100g":20,"fiber_100g":8,"proteins_100g":5,"fat_100g":1}}"""))!!
        assertEquals(28, m.carbsG) // 20 + 8
        assertEquals(8, m.fiberG)
    }

    @Test fun optionalMacrosNullWhenMissingZeroWhenPresent() {
        val missing = parseOffProduct(off("""{"product_name":"X","nutriments":{"energy-kcal_100g":100}}"""))!!
        assertNull(missing.fiberG)
        assertNull(missing.sugarsG)
        assertNull(missing.satFatG)
        assertNull(missing.omega3G)
        assertNull(missing.cholesterolMg)
        val zero = parseOffProduct(off("""{"product_name":"Y","nutriments":{
            "energy-kcal_100g":100,"fiber_100g":0,"sugars_100g":0,"saturated-fat_100g":0,
            "omega-3-fat_100g":0,"cholesterol_100g":0}}"""))!!
        assertEquals(0, zero.fiberG)
        assertEquals(0, zero.sugarsG)
        assertEquals(0, zero.satFatG)
        assertEquals(0, zero.omega3G)
        assertEquals(0, zero.cholesterolMg) // 0 g → 0 mg
    }

    @Test fun scalesDetailAndCholesterolToServing() {
        val m = parseOffProduct(off("""{"product_name":"Oil","serving_quantity":"50",
            "nutriments":{"energy-kcal_100g":800,"fat_100g":90,"fiber_100g":2,"carbohydrates_100g":0,
            "omega-3-fat_100g":10,"omega-6-fat_100g":20,"omega-9-fat_100g":30,
            "soluble-fiber_100g":1,"insoluble-fiber_100g":1,"cholesterol_100g":0.2}}"""))!!
        assertEquals(1, m.fiberG) // 2 * 0.5
        assertEquals(1, m.carbsG) // available 0 + fiber 2, scaled → 1
        assertEquals(5, m.omega3G)
        assertEquals(10, m.omega6G)
        assertEquals(15, m.omega9G)
        assertEquals(1, m.solubleFiberG)
        assertEquals(1, m.insolubleFiberG)
        assertEquals(100, m.cholesterolMg) // 0.2 g/100g * 50/100 * 1000 = 100 mg
    }

    @Test fun carbsWithoutFiberStayAvailableCarbs() {
        val m = parseOffProduct(off("""{"product_name":"Rice","nutriments":{
            "energy-kcal_100g":130,"carbohydrates_100g":28,"proteins_100g":2,"fat_100g":0}}"""))!!
        assertEquals(28, m.carbsG)
        assertNull(m.fiberG)
    }

    @Test fun parseOffSnapshotKeepsPer100AndCapsRaw() {
        val json = off("""{"product_name":"Oats","serving_size":"40 g","nutriments":{"energy-kcal_100g":379,"proteins_100g":13,"carbohydrates_100g":60,"fiber_100g":10,"fat_100g":7,"sugars_100g":1}}""")
        val snap = parseOffSnapshot(json, lookedUpAt = 42L)!!
        assertTrue(snap.lookupOk)
        assertEquals("Oats", snap.productName)
        assertEquals("40 g", snap.servingSize)
        assertEquals(42L, snap.lookedUpAt)
        assertNotNull(snap.per100Json)
        assertEquals(json, snap.rawJson)
        val macros = snap.toMacros(100.0)!!
        assertEquals(379, macros.kcal)
        assertEquals(13, macros.proteinG)
        // US-style carbs = available + fiber
        assertEquals(70, macros.carbsG)
        val round = OffSnapshot.decode(snap.encode())!!
        assertEquals(snap.productName, round.productName)
        assertTrue(round.lookupOk)
    }

    @Test fun failedSnapshotEncodesAndDecodes() {
        val s = OffSnapshot.failed(99L)
        assertFalse(s.lookupOk)
        val d = OffSnapshot.decode(s.encode())!!
        assertEquals(99L, d.lookedUpAt)
        assertFalse(d.lookupOk)
        assertNull(d.per100Json)
        assertNull(s.toMacros(100.0))
    }

    @Test fun rawJsonOverCapIsDropped() {
        val huge = "x".repeat(OffSnapshot.MAX_RAW_CHARS + 10)
        val snap = OffSnapshot(productName = "X", lookedUpAt = 1, lookupOk = true, per100Json = """{"kcal":1}""", rawJson = huge)
        assertNull(OffSnapshot.decode(snap.encode())!!.rawJson)
    }

}
