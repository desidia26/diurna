package app.diurna.ui

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Regression for debug report 657dd099 ("editing a meal, the second photo goes away").
 * The edit dialog seeds its photo list once, with rememberSaveable. It used to be seeded on the
 * first frame, before the entry's gallery had loaded from Room, so it started with only the
 * primary photo and Save replaced the gallery with that one photo. CI emulator only.
 */
class FoodEditPhotosTest {
    @get:Rule val compose = createComposeRule()

    @Before fun emulatorOnly() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk"))
    }

    /** Mirrors FoodEditDialog: whatever the dialog is first given is what it keeps. */
    private fun seededPhotos(gallery: kotlinx.coroutines.flow.Flow<List<String>>, primary: String?): List<String>? {
        var seeded: List<String>? = null
        compose.setContent {
            val initial = rememberSavedGallery(7L, primary, gallery)
            if (initial != null) {
                val photos by rememberSaveable { mutableStateOf(initial) }
                seeded = photos
            }
        }
        compose.waitUntil(5_000) { seeded != null }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        return seeded
    }

    @Test fun dialogStartsWithTheWholeGalleryWhenItLoadsLate() {
        // Room emits after the first frame, like this.
        val gallery = flow { delay(150); emit(listOf("first.jpg", "second.jpg")) }
        assertEquals(listOf("first.jpg", "second.jpg"), seededPhotos(gallery, primary = "first.jpg"))
    }

    @Test fun entryWithoutGalleryRowsStartsWithItsPrimaryPhoto() {
        val gallery = flow { delay(150); emit(emptyList<String>()) }
        assertEquals(listOf("legacy.jpg"), seededPhotos(gallery, primary = "legacy.jpg"))
    }
}
