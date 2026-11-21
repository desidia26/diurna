package app.diurna.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import app.diurna.data.LibraryRepository
import app.diurna.data.MealEntity
import app.diurna.data.RecipeIngredientEntity
import app.diurna.data.recipe.parseRecipeJsonLd
import app.diurna.data.recipe.toIngredientEntities
import app.diurna.ui.theme.DiurnaTheme
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Report r1120: an imported recipe "wouldn't let me save". */
class RecipeDialogSaveTest {
    @get:Rule val compose = createComposeRule()

    private val recipe = parseRecipeJsonLd("""{"@type":"Recipe","name":"Homemade Alfredo Sauce","recipeYield":[5],
        "recipeIngredient":["2 Tablespoons unsalted butter","2 cloves garlic (minced)","1 cup heavy cream",
        "1 1/2 cups Parmesan cheese","1/8 teaspoon salt","1/8 teaspoon black pepper"]}""")!!

    private fun show(fill: suspend (List<RecipeIngredientEntity>) -> List<RecipeIngredientEntity>, saved: MutableList<List<RecipeIngredientEntity>>, events: MutableList<String>) {
        compose.setContent {
            DiurnaTheme {
                RecipeDialog(
                    title = "Import recipe", initial = recipe.toMealDraft(), initialLines = recipe.toIngredientEntities(),
                    detailed = false, hasFdcKey = false, savedMeals = emptyList<MealEntity>(),
                    searchFdc = { emptyList() }, loadFdcNutrition = { null },
                    scanBarcode = { _, _ -> error("unused") },
                    fillDensities = fill,
                    logEvent = { name, d, _ -> events += name + ":" + (d["reason"] ?: "") },
                    onDismiss = {},
                    onSave = { _, lines -> saved += lines; LibraryRepository.Result.Ok },
                )
            }
        }
    }

    @Test fun saveTappedDuringLookupWaitsAndSaves() {
        val saved = mutableListOf<List<RecipeIngredientEntity>>()
        val events = mutableListOf<String>()
        show({ ls -> delay(300); ls.map { it.copy(grams = 10.0) } }, saved, events)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals(6, saved.single().size)
        assertTrue(saved.single().all { it.grams != null })
        assertTrue(events.toString(), "recipe.save_waited:looking_up" in events)
    }

    @Test fun anUnresolvedLineIsNamedAndCanBeWeighed() {
        val saved = mutableListOf<List<RecipeIngredientEntity>>()
        val events = mutableListOf<String>()
        // Lookup resolves everything but the garlic.
        show({ ls -> ls.map { if (it.unit == "each") it else it.copy(grams = 10.0) } }, saved, events)
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("recipeSaveProblem").assertIsDisplayed().assertTextContains("cloves garlic", substring = true)
        assertTrue(saved.isEmpty())
        assertTrue(events.toString(), "recipe.save_blocked:needs_weight" in events)
        compose.onNodeWithText("Weight").performClick()
        compose.onNodeWithTag("ingredientWeight").performTextInput("10")
        compose.onNodeWithText("Set").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals(10.0, saved.single()[1].grams!!, 1e-9)
    }
}
