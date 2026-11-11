package app.diurna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.diurna.data.LibraryRepository
import app.diurna.data.LibraryRepository.ScanResult
import app.diurna.debug.Screen
import app.diurna.ui.theme.LocalStatusColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Meal from photo: what the confirmed packages add up to, and a name for the entry. */
object MealFromPhoto {
    const val MAX_NAME = 60

    /** "Oats, almonds and pecans"; with more than three, "Oats, almonds, pecans + 2 more". Never longer than [MAX_NAME]. */
    fun defaultName(names: List<String>): String {
        val n = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val name = when {
            n.isEmpty() -> "Meal from photo"
            n.size == 1 -> n[0]
            n.size <= 3 -> n.dropLast(1).joinToString(", ") + " and " + n.last()
            else -> n.take(3).joinToString(", ") + " + ${n.size - 3} more"
        }
        return if (name.length <= MAX_NAME) name else name.take(MAX_NAME - 1).trimEnd() + "…"
    }

    fun totalKcal(lines: List<IngredientDraft>): Int = lines.sumOf { it.kcal }

    /** One line of the summary: "Rolled oats · 40 g · 150 kcal". */
    fun line(d: IngredientDraft): String = listOfNotNull(
        d.name.ifBlank { "Unnamed" },
        d.grams?.let { "${it.roundToInt()} g" } ?: "${fmt(d.amount)} ${d.unit.key}",
        "${d.kcal} kcal",
    ).joinToString(" · ")

    private fun fmt(x: Double) = if (x == x.roundToInt().toDouble()) x.roundToInt().toString() else "%.1f".format(x)
}

/**
 * Take or choose a photo of what went into a meal → confirm each package and its amount (the
 * one-photo confirm screen) → name it and log it as one entry today, optionally saving it as a
 * Library recipe. [onLog] returns a problem to show (e.g. a recipe name already taken).
 */
@Composable
fun MealFromPhotoFlow(
    scan: suspend (String, Boolean) -> ScanResult,
    onDismiss: () -> Unit,
    onLog: suspend (name: String, lines: List<IngredientDraft>, saveToLibrary: Boolean) -> LibraryRepository.Result,
) {
    var lines by remember { mutableStateOf<List<IngredientDraft>?>(null) }
    val confirmed = lines
    if (confirmed == null) {
        OnePhotoIngredientDialog(scan = scan, onDismiss = onDismiss, onAdd = { if (it.isEmpty()) onDismiss() else lines = it }, screen = Screen.DAY)
    } else {
        LogMealFromPhotoDialog(confirmed, onDismiss = onDismiss, onLog = onLog)
    }
}

@Composable
internal fun LogMealFromPhotoDialog(
    lines: List<IngredientDraft>,
    onDismiss: () -> Unit,
    onLog: suspend (String, List<IngredientDraft>, Boolean) -> LibraryRepository.Result,
) {
    var name by remember { mutableStateOf(MealFromPhoto.defaultName(lines.map { it.name })) }
    var save by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log meal") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(MealFromPhoto.MAX_NAME); problem = null }, Modifier.fillMaxWidth().testTag("mealFromPhotoName"),
                    label = { Text("Name") }, singleLine = true)
                lines.forEach { Text(MealFromPhoto.line(it), style = MaterialTheme.typography.bodyMedium) }
                Text("Total ${MealFromPhoto.totalKcal(lines)} kcal", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(save, { save = it; problem = null }, Modifier.testTag("mealFromPhotoSave"))
                    Text("Also save as a recipe in the Library")
                }
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    val r = onLog(name, lines, save)
                    busy = false
                    if (r is LibraryRepository.Result.Problem) problem = r.message
                }
            }) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
