package app.diurna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.data.LibraryRepository
import app.diurna.data.MealEntity
import app.diurna.data.recipe.ImportedRecipe
import app.diurna.data.recipe.RecipeImportResult
import app.diurna.data.recipe.toIngredientEntities
import app.diurna.domain.FoodLink
import java.net.URI

private sealed interface LinkStep {
    data object Fetching : LinkStep
    data class Failed(val message: String) : LinkStep
    data class AlreadySaved(val saved: MealEntity, val recipe: ImportedRecipe) : LinkStep
    data class Confirm(val recipe: ImportedRecipe) : LinkStep
}

/**
 * A link typed in the food box: the Library's "Import recipe from URL" (hardened HTTPS fetch,
 * schema.org Recipe), then its recipe editor as the confirm step, which saves the recipe to the
 * Library. [onRecipeReady] gets the saved recipe so the usual servings step can log it today.
 * If nothing imports, [link]'s words (never the bare link) may be estimated instead via [onEstimate].
 */
@Composable
internal fun FoodLinkFlow(
    link: FoodLink.Route.Link,
    vm: DayViewModel,
    detailed: Boolean,
    savedMeals: List<MealEntity>,
    onDismiss: () -> Unit,
    onEstimate: (String) -> Unit,
    onRecipeReady: (MealEntity) -> Unit,
) {
    var step by remember(link) { mutableStateOf<LinkStep>(LinkStep.Fetching) }
    val hasFdcKey by vm.hasFdcKey.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        vm.logFoodLink("routed")
        step = when (val r = vm.importRecipeFromUrl(link.url)) {
            is RecipeImportResult.Ok -> {
                val same = savedMeals.firstOrNull { it.isRecipe && it.name.trim().equals(r.recipe.name.trim(), ignoreCase = true) }
                if (same != null) LinkStep.AlreadySaved(same, r.recipe) else LinkStep.Confirm(r.recipe)
            }
            is RecipeImportResult.Problem -> LinkStep.Failed(r.message)
        }
    }
    when (val s = step) {
        LinkStep.Fetching -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Importing recipe") },
            text = { Text("Reading the recipe from ${hostOf(link.url)}…") },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        is LinkStep.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("foodLinkFailed"),
            title = { Text("Couldn't import a recipe") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${s.message.trimEnd('.')}.")
                    if (link.fallback != null) {
                        Hint("You can have it estimated from the link's words instead: \u201c${link.fallback}\u201d.")
                    } else {
                        Hint("The link has no words to estimate from, so nothing was guessed. Type what you ate instead, or paste the recipe page's own link.")
                    }
                }
            },
            confirmButton = {
                val fallback = link.fallback
                if (fallback != null) {
                    TextButton(onClick = { vm.logFoodLink("fallback_estimate"); onEstimate(fallback) }) { Text("Estimate instead") }
                } else {
                    TextButton(onClick = { vm.logFoodLink("fallback_none"); onDismiss() }) { Text("OK") }
                }
            },
            dismissButton = if (link.fallback != null) {
                { TextButton(onClick = onDismiss) { Text("Cancel") } }
            } else null,
        )
        is LinkStep.AlreadySaved -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Already in your recipes") },
            text = { Text("You saved \u201c${s.saved.name}\u201d before. Log that one, or import this page again under a new name.") },
            confirmButton = { TextButton(onClick = { onRecipeReady(s.saved) }) { Text("Log saved recipe") } },
            dismissButton = { TextButton(onClick = { step = LinkStep.Confirm(s.recipe) }) { Text("Import again") } },
        )
        is LinkStep.Confirm -> {
            var saved by remember(s) { mutableStateOf<MealEntity?>(null) }
            RecipeDialog(
                title = "Import recipe",
                initial = s.recipe.toMealDraft(),
                initialLines = s.recipe.toIngredientEntities(),
                detailed = detailed,
                hasFdcKey = hasFdcKey,
                savedMeals = savedMeals,
                searchFdc = vm::searchFdc,
                loadFdcNutrition = vm::loadFdcNutrition,
                scanBarcode = vm::scanBarcode,
                fillDensities = vm::resolveDensities,
                // The editor closes itself after a good save; then it's on to servings.
                onDismiss = { saved?.let(onRecipeReady) ?: onDismiss() },
                onSave = { meal, lines ->
                    val (r, recipe) = vm.saveImportedRecipe(meal, lines)
                    if (r == LibraryRepository.Result.Ok) saved = recipe
                    r
                },
            )
        }
    }
}

private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: "that page"
