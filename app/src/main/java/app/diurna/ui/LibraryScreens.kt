package app.diurna.ui

import androidx.compose.foundation.clickable
import app.diurna.R
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import app.diurna.domain.Cardio
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.data.CatalogExercise
import app.diurna.data.LibraryRepository.Result
import app.diurna.data.MealEntity
import app.diurna.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/** "160 kcal · P 30 · C 5 · F 3" */
fun MealEntity.macroLine(): String = "%,d kcal · P %d · C %d · F %d".format(kcal, proteinG, carbsG, fatG)

@Composable
fun LibraryCard(
    mealCount: Int, exerciseCount: Int, cardio: Boolean, onCardio: (Boolean) -> Unit, onMeals: () -> Unit, onExercises: () -> Unit,
) {
    SectionCard("Library") {
        Hint("Saved meals log with exact numbers and no AI call. Exercises are the names workouts and suggestions draw from.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onMeals) { Text("Meals ($mealCount)") }
            OutlinedButton(onClick = onExercises) { Text("Exercises ($exerciseCount)") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Cardio exercises", fontWeight = FontWeight.SemiBold)
                Hint("Runs, rides, rows and machines like the elliptical, logged by distance, time and pace instead of weight × reps. Off keeps them out of suggestions and Library.")
            }
            Switch(checked = cardio, onCheckedChange = onCardio)
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
    val cardioOn by vm.cardio.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<CatalogExercise?>(null) }
    var newName by remember { mutableStateOf("") }
    var addProblem by remember { mutableStateOf<String?>(null) }
    // Null: strength. Only offered with Setup's Cardio switch on.
    var newCardio by remember { mutableStateOf<Cardio.Style?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageHeader("Exercises", onBack) }
        item {
            Hint(
                "Renaming keeps history: workouts point at the exercise, not its spelling. Exercises with history can't be deleted, only hidden from suggestions." +
                    if (cardioOn) " Cardio exercises log distance, time and pace instead of weight × reps." else ""
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    newName, { newName = it; addProblem = null }, Modifier.weight(1f), placeholder = { Text("New exercise") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                if (cardioOn) {
                    FilterChip(newCardio != null, { newCardio = if (newCardio == null) Cardio.Style.TIME else null }, label = { Text("Cardio") })
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        when (val r = vm.addExercise(newName, newCardio.takeIf { cardioOn })) {
                            Result.Ok -> { newName = ""; newCardio = null }
                            is Result.Problem -> addProblem = r.message
                        }
                    }
                }) { Text("Add") }
            }
            newCardio?.takeIf { cardioOn }?.let { st -> StylePicker(st) { newCardio = it } }
            addProblem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
        }
        items(catalog, key = { it.id }) { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, color = if (e.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Hint(
                        listOfNotNull(
                            e.style.label.lowercase().takeIf { e.isCardio },
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
    renaming?.let { e ->
        // With cardio off this is the plain rename it always was; cardio already logged still gets its style.
        val editKind = cardioOn || e.isCardio
        RenameDialog(e, editKind, onDismiss = { renaming = null }) { name, cardio ->
            val r = vm.renameExercise(e.id, name)
            val current = e.style.takeIf { e.isCardio }
            if (r == Result.Ok && editKind && cardio != current) vm.setExerciseCardio(e.id, cardio) else r
        }
    }
}

/** How a cardio exercise asks for and shows its numbers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StylePicker(selected: Cardio.Style, onPick: (Cardio.Style) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Cardio.Style.entries.forEach { st -> FilterChip(st == selected, { onPick(st) }, label = { Text(st.label) }) }
    }
}

@Composable
private fun RenameDialog(e: CatalogExercise, editKind: Boolean, onDismiss: () -> Unit, onRename: suspend (String, Cardio.Style?) -> Result) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(e.name) }
    // Null: strength.
    var cardio by remember { mutableStateOf(e.style.takeIf { e.isCardio }) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editKind) "Edit exercise" else "Rename exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; problem = null }, Modifier.fillMaxWidth(), singleLine = true)
                if (e.logged > 0) Hint("Past workouts will show the new name.")
                if (editKind) {
                    // Kind only changes before anything is logged, so sets and pieces never mix in history.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            cardio != null, { cardio = if (cardio == null) Cardio.Style.TIME else null },
                            enabled = e.logged == 0, label = { Text("Cardio") },
                        )
                        Hint(if (e.logged == 0) "Distance, time and pace instead of weight × reps" else "Logged exercises keep their kind")
                    }
                    cardio?.let { st ->
                        StylePicker(st) { cardio = it }
                        Hint("Changes which numbers it asks for and how pace reads. Logged pieces keep their numbers.")
                    }
                }
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    when (val r = onRename(name, cardio)) {
                        Result.Ok -> onDismiss()
                        is Result.Problem -> problem = r.message
                    }
                }
            }) { Text(if (editKind) "Save" else "Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
