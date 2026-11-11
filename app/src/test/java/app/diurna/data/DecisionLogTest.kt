package app.diurna.data

import app.diurna.ai.PackagePhotoReading
import app.diurna.ai.PackageQuestion
import app.diurna.ai.PackageReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Append-only decisions plus immutable interpretation revisions, stored with the ingredient. */
class DecisionLogTest {
    private val oats = PackageReading(true, "Old Fashioned Oats", "Acme", "1/2 cup dry (40g)", 40.0, 13.0,
        mapOf("kcal" to 150.0, "fat_g" to 3.0), "WHOLE GRAIN ROLLED OATS")
    private val original = PackagePhotoReading(listOf(oats), listOf(PackageQuestion(0, "serving_basis", "Dry or prepared?", listOf("Dry", "Prepared"))))
    private val prepared = PackagePhotoReading(listOf(oats.copy(perServing = mapOf("kcal" to 250.0, "fat_g" to 7.0), servingText = "1/2 cup dry + 1 cup milk")))

    @Test fun entriesAreOrderedAndAppendOnly() {
        val a = DecisionLog().add(DecisionLog.SOURCE, "Label read by AI")
        val b = a.add(DecisionLog.QUESTION, "Dry or prepared?", product = 0)
        assertEquals(1, a.entries.size) // the earlier log is unchanged
        assertEquals(listOf(1, 2), b.entries.map { it.seq })
        assertEquals(0, b.entries[1].product)
    }

    @Test fun refinementKeepsTheOriginalAndCanRevert() {
        val log = DecisionLog().addRevision(original, DecisionLog.ORIGIN_READ)
            .addRevision(prepared, DecisionLog.ORIGIN_REFINE, answer = "Prepared")
        assertEquals(2, log.revisions.size)
        assertEquals(1, log.current)
        assertEquals(original, log.revisions[0].reading)
        assertEquals("Prepared", log.revisions[1].answer)
        assertEquals(0, log.revisions[1].basedOn)
        val back = log.revert(0)
        assertEquals(original, back.reading)
        assertEquals(2, back.revisions.size) // nothing deleted
        assertEquals(DecisionLog.REVERT, back.entries.last().kind)
    }

    @Test fun diffShowsWhatTheRefinementChanged() {
        val lines = diffReadings(original, prepared)
        assertTrue(lines.toString(), lines.any { "calories" in it && "150" in it && "250" in it })
        assertTrue(lines.any { "serving" in it })
        assertTrue(lines.none { "ingredients" in it })
    }

    @Test fun roundTripsThroughJson() {
        val log = DecisionLog().addRevision(original, DecisionLog.ORIGIN_READ)
            .add(DecisionLog.FIELD, "kcal 375 per 100 g from Label", product = 0)
            .addRevision(prepared, DecisionLog.ORIGIN_REFINE, answer = "Prepared")
        val back = DecisionLog.fromJson(log.toJson())!!
        assertEquals(log, back)
    }

    @Test fun corruptJsonIsNull() {
        assertNull(DecisionLog.fromJson("{"))
        assertNull(DecisionLog.fromJson(null))
    }

    @Test fun boundedRevisionsAndEntries() {
        var log = DecisionLog()
        repeat(DecisionLog.MAX_REVISIONS + 3) { log = log.addRevision(original, DecisionLog.ORIGIN_REFINE, answer = "a$it") }
        assertEquals(DecisionLog.MAX_REVISIONS, log.revisions.size)
        assertTrue(!log.canRefine)
        repeat(DecisionLog.MAX_ENTRIES + 50) { log = log.add(DecisionLog.EDIT, "e$it") }
        assertEquals(DecisionLog.MAX_ENTRIES + 1, log.entries.size)
        assertEquals(DecisionLog.TRUNCATED, log.entries.last().kind)
    }
}
