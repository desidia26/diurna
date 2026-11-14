package app.diurna.debug

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Debug report r657 ("editing a meal, the second photo goes away"): edits now log photo counts, never names. */
class FoodEditLoggingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun recorder(on: Boolean = true) = ActionRecorder(tmp.newFolder()).apply { enabled = on; includeTypedText = true }
    private fun log(rec: ActionRecorder) = rec.snapshot().map { JSONObject(it) }

    @Test fun anEditThatDropsAPhotoShowsItWasRemoved() {
        val d = FoodEditLogging.saveDetails(before = listOf("a.jpg", "b.jpg"), after = listOf("a.jpg"))
        assertEquals(mapOf("photos_before" to "2", "photos_after" to "1", "photos_added" to "0", "photos_removed" to "1"), d)
    }

    @Test fun addingAndReplacingAreCountedSeparately() {
        val d = FoodEditLogging.saveDetails(before = listOf("a.jpg", "b.jpg"), after = listOf("a.jpg", "c.jpg", "d.jpg"))
        assertEquals("3", d["photos_after"]); assertEquals("2", d["photos_added"]); assertEquals("1", d["photos_removed"])
        assertEquals("0", FoodEditLogging.saveDetails(listOf("a.jpg"), listOf("a.jpg", "a.jpg"))["photos_added"])
    }

    @Test fun recordsCountsButNeverPhotoNames() {
        val rec = recorder()
        FoodEditLogging.logOpen(rec.store, shown = 2)
        FoodEditLogging.logSave(rec.store, before = listOf("secret-name-1.jpg", "secret-name-2.jpg"), after = listOf("secret-name-1.jpg"))
        val entries = log(rec)
        val open = entries.single { it.getString("action") == FoodEditLogging.OPEN }
        assertEquals("2", open.getJSONObject("details").getString("photos"))
        val save = entries.single { it.getString("action") == FoodEditLogging.PHOTOS }.getJSONObject("details")
        assertEquals("1", save.getString("photos_removed"))
        assertEquals(setOf("photos_before", "photos_after", "photos_added", "photos_removed"), save.keys().asSequence().toSet())
        assertFalse(rec.snapshot().any { "secret-name" in it || ".jpg" in it })
    }

    @Test fun nothingIsRecordedWithDebugLoggingOff() {
        val rec = recorder(on = false)
        FoodEditLogging.logOpen(rec.store, shown = 2)
        FoodEditLogging.logSave(rec.store, listOf("a.jpg", "b.jpg"), listOf("a.jpg"))
        assertTrue(rec.snapshot().isEmpty())
    }
}
