package app.diurna.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.diurna.data.LibraryRepository.Result
import app.diurna.data.MealEntity
import app.diurna.ui.theme.DiurnaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** With detailed nutrition on, the meal form runs past a phone screen; its last fields must stay reachable. */
class MealDialogLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun lastDetailedFieldScrollsIntoViewAndSaves() {
        var saved: MealEntity? = null
        rule.setContent {
            DiurnaTheme {
                MealDialog(
                    title = "Add meal",
                    initial = MealEntity(name = "", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0),
                    detailed = true,
                    onDismiss = {},
                    onSave = { saved = it; Result.Ok },
                )
            }
        }
        rule.onNode(hasText("Name") and hasSetTextAction()).performTextInput("Oats")
        rule.onNode(hasText("Calories") and hasSetTextAction()).performTextInput("150")
        val tyr = rule.onNode(hasText("Tyr") and hasSetTextAction())
        tyr.performScrollTo().assertIsDisplayed()
        tyr.performTextInput("210")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5_000) { saved != null }
        assertEquals(210, saved!!.tyrMg)
    }
}
