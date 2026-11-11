package app.diurna.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.diurna.ai.PackagePhotoReading
import app.diurna.ai.PackageQuestion
import app.diurna.ai.PackageReading
import app.diurna.data.DecisionLog
import app.diurna.data.LibraryRepository.ScanResult
import app.diurna.data.MealEntity
import app.diurna.data.OffSnapshot
import app.diurna.data.OnePhotoCapture
import app.diurna.data.OnePhotoCapture.Capture
import app.diurna.data.OnePhotoCapture.CodeRead
import app.diurna.data.OnePhotoCapture.LabelStatus
import app.diurna.data.parseOffSnapshot
import app.diurna.domain.PackageMerge
import app.diurna.ui.theme.DiurnaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The one-photo confirm screen: rows per package, sources, disagreements, guesses, questions, one Save. */
class OnePhotoConfirmTest {
    @get:Rule val compose = createComposeRule()

    private val code = "0860009719569"
    private val flax = PackageReading(true, "Flax", "Acme", "1 Tbsp (7g)", 7.0, 86.0,
        mapOf("kcal" to 35.0, "protein_g" to 1.4, "carbs_g" to 2.1, "fat_g" to 2.8), "FLAXSEEDS")
    private val almonds = PackageReading(true, "Almonds", "Mariani", "1 oz (28g)", 28.0, 16.0,
        mapOf("kcal" to 170.0, "protein_g" to 6.0, "carbs_g" to 6.0, "fat_g" to 15.0), "ALMONDS")

    private fun offFound(): ScanResult {
        val snap = parseOffSnapshot("""{"status":1,"product":{"product_name":"Flax","serving_quantity":7,
            "nutriments":{"energy-kcal_100g":600,"proteins_100g":20,"carbohydrates_100g":30,"fat_100g":40}}}""", 1L)!!
        return ScanResult.Found(MealEntity(name = "Flax", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0, barcode = code, offSnapshotJson = snap.encode()))
    }

    private fun read(vararg p: PackageReading, questions: List<PackageQuestion> = emptyList()) =
        DecisionLog().addRevision(PackagePhotoReading(p.toList(), questions), DecisionLog.ORIGIN_READ)

    private class Calls {
        val saved = mutableListOf<IngredientDraft>()
        val refined = mutableListOf<String>()
        val picked = mutableListOf<Pair<String, String?>>()
    }

    private fun show(c: Capture, calls: Calls = Calls()): Calls {
        compose.setContent {
            DiurnaTheme {
                OnePhotoConfirm(c, onChooseCode = {}, onRetake = {}, onLiveScan = {}, onReadLabel = {},
                    onRefine = { calls.refined += it }, onRevert = {}, onPick = { k, r -> calls.picked += k to r }, onNote = { _, _, _ -> },
                    onSave = { calls.saved += it }, onCancel = {})
            }
        }
        return calls
    }

    @Test fun showsSourcesAndDisagreementsAndSavesServings() {
        val calls = show(Capture(LabelStatus.Read, read(flax), listOf(CodeRead(code, null, offFound(), null))))
        compose.onNodeWithText("Label").assertExists()
        compose.onNodeWithText("OFF 600", substring = true).assertExists()
        compose.onNodeWithTag("servings-p0").assertIsEnabled().performClick()
        compose.onNodeWithTag("amount-p0").performTextInput("2")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            val d = calls.saved.single()
            assertEquals(70, d.kcal)
            assertEquals(14.0, d.grams!!, 1e-9)
            assertEquals("Acme Flax", d.name)
            assertEquals(code, d.barcode)
            assertTrue(DecisionLog.fromJson(d.captured!!.decisionLogJson)!!.entries.any { it.kind == DecisionLog.DISAGREEMENT })
        }
    }

    @Test fun nothingFoundKeepsTheBarcodeAndNeedsManualValuesBeforeSave() {
        val blank = MealEntity(name = "", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0, barcode = code, offSnapshotJson = OffSnapshot.failed().encode())
        val key = "c$code"
        val calls = show(Capture(LabelStatus.Skipped("offline", "Offline, so the label wasn't read."), DecisionLog(), listOf(CodeRead(code, null, ScanResult.NotFound(blank), null))))
        compose.onNodeWithText("Offline, so the label wasn't read.", substring = true).assertExists()
        compose.onNodeWithTag("servings-$key").assertIsNotEnabled()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithTag("name-$key").performTextInput("Seeds")
        compose.onNodeWithTag("amount-$key").performTextInput("50")
        compose.onNodeWithTag("fill-$key-kcal").performTextInput("500")
        compose.onNodeWithTag("fill-$key-protein_g").performTextInput("20")
        compose.onNodeWithTag("fill-$key-carbs_g").performTextInput("30")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithTag("fill-$key-fat_g").performTextInput("40")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        compose.runOnIdle {
            val d = calls.saved.single()
            assertEquals(250, d.kcal)
            assertEquals(code, d.barcode)
            assertTrue(d.captured!!.labelPending())
            assertNull(d.nutrition.fiberG)
        }
    }

    @Test fun severalPackagesSaveTogetherAndARemovedOneIsLeftOut() {
        val pecans = flax.copy(productName = "Pecans", brand = "")
        val calls = show(Capture(LabelStatus.Read, read(flax, almonds, pecans)))
        compose.onNodeWithText("3 packages").assertExists()
        compose.onNodeWithTag("amount-p0").performTextInput("14")
        compose.onNodeWithTag("amount-p1").performScrollTo().performTextInput("28")
        compose.onNodeWithText("Save 3").assertIsNotEnabled()
        compose.onNodeWithTag("remove-p2").performScrollTo().performClick()
        compose.onNodeWithText("Save 2").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("Acme Flax", "Mariani Almonds"), calls.saved.map { it.name })
            assertEquals(listOf(70, 170), calls.saved.map { it.kcal })
        }
    }

    @Test fun aNameGuessMustBePickedAndIsMarkedAsAGuess() {
        val front = PackageReading(false, "Flax Seeds", "Roots Circle", "", null, null, emptyMap(), null)
        val input = PackageMerge.Input(PackageMerge.Source.OFF, per100g = mapOf("kcal" to 534.0, "protein_g" to 18.0, "carbs_g" to 29.0, "fat_g" to 42.0))
        val cand = OnePhotoCapture.Candidate("off:1", PackageMerge.Source.OFF, "Roots Circle Flax seeds", input, null)
        val base = Capture(LabelStatus.Read, read(front), guesses = mapOf("p0" to listOf(cand)))
        val calls = show(base)
        compose.onNodeWithText("Guess: Roots Circle Flax seeds (OFF)").performClick()
        compose.runOnIdle { assertEquals(listOf("p0" to "off:1"), calls.picked) }
        // Not picked yet: no values from the guess.
        compose.onNodeWithTag("fill-p0-kcal").assertExists()
    }

    @Test fun pickedGuessShowsTheGuessChip() {
        val front = PackageReading(false, "Flax Seeds", "Roots Circle", "", null, null, emptyMap(), null)
        val input = PackageMerge.Input(PackageMerge.Source.OFF, per100g = mapOf("kcal" to 534.0, "protein_g" to 18.0, "carbs_g" to 29.0, "fat_g" to 42.0))
        val cand = OnePhotoCapture.Candidate("off:1", PackageMerge.Source.OFF, "Roots Circle Flax seeds", input, null)
        show(Capture(LabelStatus.Read, read(front), guesses = mapOf("p0" to listOf(cand)), picked = mapOf("p0" to "off:1")))
        compose.onNodeWithText("Guess · OFF").assertExists()
    }

    @Test fun questionsOfferSuggestedAnswersAndFreeTextRefinement() {
        val q = PackageQuestion(0, "serving_basis", "Use the Dry or the Prepared column?", listOf("Dry", "Prepared"))
        val calls = show(Capture(LabelStatus.Read, read(flax, questions = listOf(q))))
        compose.onNodeWithText("AI asks: Use the Dry or the Prepared column?").assertExists()
        compose.onNodeWithText("Prepared").performClick()
        compose.onNodeWithTag("refine").performTextInput("It's the 16 oz bag")
        compose.onNodeWithText("Refine").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Q: Use the Dry or the Prepared column?\nA: Prepared", "It's the 16 oz bag"), calls.refined)
        }
    }

    @Test fun decisionsAreViewable() {
        show(Capture(LabelStatus.Read, read(flax).add(DecisionLog.SOURCE, "Open Food Facts for 0860009719569: not found")))
        compose.onAllNodesWithText("Decisions", substring = true)[0].performScrollTo().performClick()
        compose.onNodeWithText("[source] Open Food Facts", substring = true).assertExists()
    }
}
