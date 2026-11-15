package app.diurna.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.diurna.data.LibraryRepository
import app.diurna.ui.theme.DiurnaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MealFromPhotoTest {
    @get:Rule val compose = createComposeRule()

    private val lines = listOf(
        IngredientDraft(name = "Rolled oats", amount = 40.0, grams = 40.0, kcal = 150),
        IngredientDraft(name = "Sliced almonds", amount = 15.0, grams = 15.0, kcal = 85),
    )

    @Test fun plusMenuOffersMealFromPhoto() {
        var picked = 0
        compose.setContent {
            DiurnaTheme {
                AttachMenu(true, PhotoAttacher({}, {}, { false }, { null }), onClose = {}, onScan = {}, onMealFromPhoto = { picked++ })
            }
        }
        compose.onNodeWithText("Meal from photo").assertIsDisplayed().performClick()
        assertEquals(1, picked)
    }

    @Test fun logDialogNamesTheMealAndCanSaveTheRecipe() {
        val calls = mutableListOf<Triple<String, Int, Boolean>>()
        compose.setContent {
            DiurnaTheme {
                LogMealFromPhotoDialog(lines, onDismiss = {}, onLog = { name, l, save -> calls += Triple(name, l.size, save); LibraryRepository.Result.Ok })
            }
        }
        compose.onNodeWithText("Rolled oats · 40 g · 150 kcal").assertIsDisplayed()
        compose.onNodeWithText("Total 235 kcal").assertIsDisplayed()
        compose.onNodeWithTag("mealFromPhotoSave").performClick()
        compose.onNodeWithText("Log").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("Rolled oats and Sliced almonds", 2, true)), calls)
    }

    @Test fun aTakenRecipeNameIsShownAndTheDialogStays() {
        compose.setContent {
            DiurnaTheme {
                LogMealFromPhotoDialog(lines, onDismiss = {}, onLog = { _, _, _ -> LibraryRepository.Result.Problem("You already have a meal called \"X\".") })
            }
        }
        compose.onNodeWithText("Log").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("You already have a meal called \"X\".").assertIsDisplayed()
    }
}
