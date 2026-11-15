package app.diurna.eval

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorded lookups answer like the phone's, through the app's parsers, with no network. */
class EvalLookupsTest {
    private val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "ai-evals/lookups.json") }.first { it.isFile }
    private val l = EvalLookups(file)

    @Test fun `a barcode finds USDA branded and Open Food Facts by any spelling`() = runBlocking {
        val oats = l.byCode("011110766557")
        assertEquals(listOf("usda_branded", "open_food_facts"), oats.map { it.source })
        assertTrue(oats.none { it.byName })
        assertEquals(oats, l.byCode("0011110766557"))
        // ~375 kcal per 100 g of rolled oats.
        assertTrue(oats[0].per100g.getValue("kcal") in 340.0..400.0)
        assertEquals(listOf("open_food_facts"), l.byCode("078742202334").map { it.source })
        assertTrue(l.byCode("012345678905").isEmpty())
    }

    @Test fun `a name search is a guess, matched by its recorded term`() = runBlocking {
        val c = l.byName("Ocean Spray Craisins Dried Cranberries")
        assertTrue(c.isNotEmpty() && c.all { it.byName && it.source == "usda_branded" })
        assertTrue(l.byName("Something else entirely").isEmpty())
        assertEquals(2, l.asked.size)
    }
}
