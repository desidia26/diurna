package app.diurna.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import app.diurna.ai.PackageReading
import app.diurna.data.LibraryRepository.ScanResult
import app.diurna.data.MealEntity
import app.diurna.data.OffSnapshot
import app.diurna.data.OnePhotoCapture.Capture
import app.diurna.data.OnePhotoCapture.CodeState
import app.diurna.data.OnePhotoCapture.LabelRead
import app.diurna.data.parseOffSnapshot
import app.diurna.ui.theme.DiurnaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The one-photo confirm screen: sources, disagreements, grams or servings, one Save. */
class OnePhotoConfirmTest {
    @get:Rule val compose = createComposeRule()

    private val code = "0860009719569"
    private val reading = PackageReading(true, "Flax", "Acme", "1 Tbsp (7g)", 7.0, 86.0,
        mapOf("kcal" to 35.0, "protein_g" to 1.4, "carbs_g" to 2.1, "fat_g" to 2.8), "FLAXSEEDS")

    private fun offFound(): ScanResult {
        val snap = parseOffSnapshot("""{"status":1,"product":{"product_name":"Flax","serving_quantity":7,
            "nutriments":{"energy-kcal_100g":600,"proteins_100g":20,"carbohydrates_100g":30,"fat_100g":40}}}""", 1L)!!
        return ScanResult.Found(MealEntity(name = "Flax", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0, barcode = code, offSnapshotJson = snap.encode()))
    }

    private fun show(c: Capture, saved: MutableList<IngredientDraft>) = compose.setContent {
        DiurnaTheme {
            OnePhotoConfirm(c, onChooseCode = {}, onRetake = {}, onLiveScan = {}, onReadLabel = {}, onSave = { saved += it }, onCancel = {})
        }
    }

    @Test fun showsSourcesAndDisagreementsAndSavesServings() {
        val saved = mutableListOf<IngredientDraft>()
        show(Capture(CodeState.Read(code, offFound(), null), LabelRead.Read(reading)), saved)
        compose.onNodeWithText("Label", substring = false).assertExists()
        compose.onNodeWithText("OFF 600", substring = true).assertExists()
        compose.onNodeWithText("servings").assertIsEnabled().performClick()
        compose.onNodeWithText("Amount (servings)").performTextClearance()
        compose.onNodeWithText("Amount (servings)").performTextInput("2")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(1, saved.size)
            assertEquals(70, saved[0].kcal)
            assertEquals(14.0, saved[0].grams!!, 1e-9)
            assertEquals("Acme Flax", saved[0].name)
            assertEquals(code, saved[0].barcode)
        }
    }

    @Test fun nothingFoundKeepsTheBarcodeAndNeedsManualValuesBeforeSave() {
        val saved = mutableListOf<IngredientDraft>()
        val blank = MealEntity(name = "", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0, barcode = code, offSnapshotJson = OffSnapshot.failed().encode())
        show(Capture(CodeState.Read(code, ScanResult.NotFound(blank), null), LabelRead.Skipped("offline", "Offline, so the label wasn't read.")), saved)
        compose.onNodeWithText("Offline, so the label wasn't read.", substring = true).assertExists()
        compose.onNodeWithText("servings").assertIsNotEnabled()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("Seeds")
        compose.onNodeWithText("Amount (g)").performTextInput("50")
        compose.onNodeWithText("Calories (kcal)").performTextInput("500")
        compose.onNodeWithText("Protein (g)").performTextInput("20")
        compose.onNodeWithText("Carbs (g)").performTextInput("30")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Fat (g)").performTextInput("40")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle {
            val d = saved.single()
            assertEquals(250, d.kcal)
            assertEquals(code, d.barcode)
            assertTrue(d.captured!!.labelPending())
            assertNull(d.nutrition.fiberG)
        }
    }
}
