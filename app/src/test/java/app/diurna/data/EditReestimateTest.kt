package app.diurna.data

import app.diurna.debug.Action
import app.diurna.debug.ActionRecorder
import app.diurna.debug.Screen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Larry: "Yes, re-estimate when photos are added or removed" (follow-up to report ea12351c). */
class EditReestimateTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ai = FoodEntryEntity(id = 1, date = "2026-11-14", time = "12:00", text = "3 links", loggedAt = 0, photo = "barcode.jpg")
    private val meal = ai.copy(source = FoodEntryEntity.SOURCE_MEAL)

    private fun decide(old: FoodEntryEntity, before: List<String>, after: List<String>, text: String = old.text) =
        EditReestimate.decide(old, text, before, after)

    @Test fun addingASecondPhotoReEstimates() {
        val d = decide(ai, listOf("barcode.jpg"), listOf("barcode.jpg", "label.jpg"))!!
        assertEquals(EditReestimate.Reason.PHOTOS, d.reason)
        assertEquals(mapOf("reason" to "photos", "photos_added" to "1", "photos_removed" to "0", "photos_moved" to "0"), d.details())
    }

    @Test fun removingAnExtraPhotoReEstimates() {
        val d = decide(ai, listOf("barcode.jpg", "label.jpg"), listOf("barcode.jpg"))!!
        assertEquals(EditReestimate.Reason.PHOTOS, d.reason)
        assertEquals(1, d.removed)
    }

    @Test fun reorderingPhotosReEstimates() {
        val d = decide(ai.copy(photo = "a.jpg"), listOf("a.jpg", "b.jpg", "c.jpg"), listOf("a.jpg", "c.jpg", "b.jpg"))!!
        assertEquals(EditReestimate.Reason.PHOTOS, d.reason)
        assertEquals(0, d.added); assertEquals(0, d.removed); assertEquals(2, d.moved)
    }

    @Test fun newPrimaryPhotoStillReEstimates() {
        assertEquals(EditReestimate.Reason.PHOTOS, decide(ai, listOf("barcode.jpg"), listOf("other.jpg"))!!.reason)
        assertEquals(EditReestimate.Reason.PHOTOS, decide(ai, listOf("barcode.jpg"), emptyList())!!.reason)
    }

    @Test fun legacyEntryGainingAGalleryReEstimates() {
        assertEquals(EditReestimate.Reason.PHOTOS, decide(ai, listOf("barcode.jpg"), listOf("barcode.jpg", "label.jpg"))!!.reason)
    }

    @Test fun unchangedPhotosAndTextKeepTheNumbers() {
        assertNull(decide(ai, listOf("barcode.jpg", "label.jpg"), listOf("barcode.jpg", "label.jpg")))
        assertNull(decide(ai.copy(photo = null), emptyList(), emptyList()))
    }

    @Test fun savedMealNumbersAreExactSoPhotosNeverReEstimate() {
        assertNull(decide(meal, listOf("barcode.jpg"), listOf("barcode.jpg", "label.jpg")))
        assertNull(decide(meal, listOf("barcode.jpg", "label.jpg"), listOf("label.jpg", "barcode.jpg")))
        assertNull(decide(meal, listOf("barcode.jpg"), listOf("other.jpg")))
    }

    @Test fun textChangesReEstimateAsBefore() {
        assertEquals(EditReestimate.Reason.TEXT, decide(ai, listOf("barcode.jpg"), listOf("barcode.jpg"), text = "4 links")!!.reason)
        assertEquals(EditReestimate.Reason.TEXT, decide(meal, listOf("barcode.jpg"), listOf("barcode.jpg", "x.jpg"), text = "4 links")!!.reason)
        assertEquals(EditReestimate.Reason.TEXT_AND_PHOTOS,
            decide(ai, listOf("barcode.jpg"), listOf("barcode.jpg", "label.jpg"), text = "4 links")!!.reason)
    }

    @Test fun theDebugLogKeepsTheReasonAndCountsButNoNames() {
        val d = decide(ai, listOf("barcode.jpg"), listOf("barcode.jpg", "label.jpg"))!!
        val on = ActionRecorder(tmp.newFolder()).apply { enabled = true }
        on.store.dispatch(Action.Event(Screen.BACKGROUND, "food.reestimate", result = Action.Result.OK, details = d.details()))
        val details = JSONObject(on.snapshot().single()).getJSONObject("details")
        assertEquals(setOf("reason", "photos_added", "photos_removed", "photos_moved"), details.keys().asSequence().toSet())
        assertEquals("photos", details.getString("reason"))
        assertFalse(on.snapshot().single().contains(".jpg"))
        val off = ActionRecorder(tmp.newFolder())
        off.store.dispatch(Action.Event(Screen.BACKGROUND, "food.reestimate", result = Action.Result.OK, details = d.details()))
        assertTrue(off.snapshot().isEmpty())
    }
}
