package app.daybook.ui

import androidx.compose.foundation.clickable
import app.daybook.R
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daybook.data.CatalogExercise
import app.daybook.data.LibraryRepository.Result
import app.daybook.data.MealEntity
import app.daybook.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/** "160 kcal · P 30 · C 5 · F 3" */
fun MealEntity.macroLine(): String = "%,d kcal · P %d · C %d · F %d".format(kcal, proteinG, carbsG, fatG)

@Composable
fun LibraryCard(mealCount: Int, exerciseCount: Int, onMeals: () -> Unit, onExercises: () -> Unit) {
    SectionCard("Library") {
        Hint("Saved meals log with exact numbers and no AI call. Exercises are the names workouts and suggestions draw from.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMeals) { Text("Meals ($mealCount)") }
            OutlinedButton(onClick = onExercises) { Text("Exercises ($exerciseCount)") }
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack, contentPadding = PaddingValues(start = 10.dp, end = 16.dp)) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null, Modifier.size(22.dp))
            Text("Setup")
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MealsPage(vm: LibraryViewModel, onBack: () -> Unit) {
    val meals by vm.meals.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<MealEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<MealEntity?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Meals", onBack) }
        item {
            Hint("Your regular foods with exact numbers, e.g. from the label. Pick one in the Day screen's food box to log it instantly.")
        }
        item { Button(onClick = { editing = MealEntity(name = "", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0) }) { Text("Add meal") } }
        if (meals.isEmpty()) item { Hint("No saved meals yet. You can also save one from any food entry on the Day screen.") }
        items(meals, key = { it.id }) { m ->
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Edit") { editing = m }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(m.name, fontWeight = FontWeight.SemiBold)
                    Text(m.macroLine(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    m.note?.let { Hint(it) }
                }
                TextButton(onClick = { confirmDelete = m }) { Text("Delete") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
    editing?.let { m ->
        MealDialog(if (m.id == 0L) "Add meal" else "Edit meal", m, onDismiss = { editing = null }, onSave = vm::saveMeal)
    }
    confirmDelete?.let { m ->
        ConfirmDialog(
            "Delete ${m.name}?", "Entries you've already logged keep their numbers.", "Delete",
            onDismiss = { confirmDelete = null },
        ) { vm.deleteMeal(m.id); confirmDelete = null }
    }
}

/** Add/edit a saved meal. Also used by "Save as meal" on a food entry. Stays open to show problems. */
@Composable
fun MealDialog(title: String, initial: MealEntity, onDismiss: () -> Unit, onSave: suspend (MealEntity) -> Result) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var kcal by remember { mutableStateOf(initial.kcal.takeIf { it > 0 }?.toString() ?: "") }
    // Blank rather than "0" for a new meal, so there's nothing to delete before typing.
    fun start(x: Int) = if (x == 0 && initial.kcal == 0) "" else x.toString()
    var p by remember { mutableStateOf(start(initial.proteinG)) }
    var c by remember { mutableStateOf(start(initial.carbsG)) }
    var f by remember { mutableStateOf(start(initial.fatG)) }
    var note by remember { mutableStateOf(initial.note ?: "") }
    var problem by remember { mutableStateOf<String?>(null) }
    val valid = name.isNotBlank() && kcal.toIntOrNull() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedTextField(kcal, { kcal = it }, Modifier.fillMaxWidth(), label = { Text("Calories") }, suffix = { Text("kcal") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple("Protein", p) { v: String -> p = v }, Triple("Carbs", c) { v: String -> c = v }, Triple("Fat", f) { v: String -> f = v }).forEach { (label, value, set) ->
                        OutlinedTextField(value, set, Modifier.weight(1f), label = { Text(label) }, suffix = { Text("g") }, placeholder = { Text("0") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Serving (optional)") }, placeholder = { Text("1 bottle, 11 oz") }, singleLine = true)
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val meal = initial.copy(
                    name = name, kcal = kcal.toIntOrNull() ?: 0, proteinG = p.toIntOrNull() ?: 0,
                    carbsG = c.toIntOrNull() ?: 0, fatG = f.toIntOrNull() ?: 0, note = note,
                )
                scope.launch {
                    when (val r = onSave(meal)) {
                        Result.Ok -> onDismiss()
                        is Result.Problem -> problem = r.message
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ExercisesPage(vm: LibraryViewModel, onBack: () -> Unit) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<CatalogExercise?>(null) }
    var newName by remember { mutableStateOf("") }
    var addProblem by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageHeader("Exercises", onBack) }
        item {
            Hint("Renaming keeps history: workouts point at the exercise, not its spelling. Exercises with history can't be deleted, only hidden from suggestions.")
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    newName, { newName = it; addProblem = null }, Modifier.weight(1f), placeholder = { Text("New exercise") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                OutlinedButton(onClick = {
                    scope.launch {
                        when (val r = vm.addExercise(newName)) {
                            Result.Ok -> newName = ""
                            is Result.Problem -> addProblem = r.message
                        }
                    }
                }) { Text("Add") }
            }
            addProblem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
        }
        items(catalog, key = { it.id }) { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, color = if (e.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Hint(
                        listOfNotNull(
                            "hidden".takeIf { e.hidden },
                            "logged ${e.logged}×".takeIf { e.logged > 0 },
                            "in a routine workout".takeIf { e.inRoutine > 0 },
                            "unused".takeIf { !e.inUse },
                        ).joinToString(" · ")
                    )
                }
                TextButton(onClick = { renaming = e }, contentPadding = PaddingValues(6.dp)) { Text("Rename") }
                if (e.inUse || e.hidden) {
                    TextButton(onClick = { vm.setExerciseHidden(e.id, !e.hidden) }, contentPadding = PaddingValues(6.dp)) { Text(if (e.hidden) "Show" else "Hide") }
                } else {
                    TextButton(onClick = { vm.deleteExercise(e.id) }, contentPadding = PaddingValues(6.dp)) { Text("Delete") }
                }
            }
        }
    }
    renaming?.let { e -> RenameDialog(e, onDismiss = { renaming = null }) { vm.renameExercise(e.id, it) } }
}

@Composable
private fun RenameDialog(e: CatalogExercise, onDismiss: () -> Unit, onRename: suspend (String) -> Result) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(e.name) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; problem = null }, Modifier.fillMaxWidth(), singleLine = true)
                if (e.logged > 0) Hint("Past workouts will show the new name.")
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    when (val r = onRename(name)) {
                        Result.Ok -> onDismiss()
                        is Result.Problem -> problem = r.message
                    }
                }
            }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
