package app.diurna.ui

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.diurna.data.FoodEntryEntity
import app.diurna.data.FoodPhotos
import app.diurna.ui.theme.DiurnaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** CI emulator only, like the other device tests. */
class FoodRowTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 11, 11)
    private lateinit var photos: FoodPhotos

    @Before fun setup() {
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("sdk"))
        photos = FoodPhotos(ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("food-row-test"))
    }

    private val burrito = FoodEntryEntity(
        id = 1, date = day.toString(), time = "12:50", text = "Chicken burrito bowl",
        loggedAt = day.atTime(12, 50).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        kcalLow = 610, kcalHigh = 780, proteinG = 44, carbsG = 72, fatG = 22,
        estimatedAt = 1L, estimateNote = "large bowl, white rice",
    )

    @Test fun collapsedIsOneLineAndTapOpensTheRest() {
        var deleted = 0
        compose.setContent {
            DiurnaTheme {
                var open by remember { mutableStateOf(false) }
                FoodRow(
                    burrito, day, online = true, photos = photos, detailed = false, gallery = emptyList(),
                    expanded = open, onToggle = { open = !open },
                    onEdit = {}, onDelete = { deleted++ }, onServings = {}, onSaveAsMeal = {},
                )
            }
        }
        compose.onNodeWithText("Chicken burrito bowl").assertIsDisplayed()
        compose.onNodeWithText("~695").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTextCount("Save as meal") == 0)

        compose.onNodeWithText("Chicken burrito bowl").performClick()
        compose.onNodeWithText("610–780 kcal · P 44 · C 72 · F 22").assertIsDisplayed()
        compose.onNodeWithText("Assumed: large bowl, white rice").assertIsDisplayed()
        compose.onNodeWithText("Save as meal").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }

    @Test fun theComposerGalleryHasNoAddButtonAndEachPhotoHasItsOwnRemove() {
        var names by mutableStateOf(listOf("a.jpg", "b.jpg"))
        compose.setContent {
            DiurnaTheme {
                val attacher = remember { PhotoAttacher({}, {}, { false }, { null }) }
                PhotoGalleryEditor(photos, names, attacher, showAdd = false, onChange = { names = it })
            }
        }
        assertTrue(compose.onAllNodesWithTextCount("Add another photo") == 0)
        compose.onNodeWithContentDescription("Remove photo 2").performClick()
        compose.waitForIdle()
        assertEquals(listOf("a.jpg"), names)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
