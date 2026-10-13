package app.daybook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daybook.data.RoutineDayEntity
import app.daybook.data.Template
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun RoutineScreen(vm: RoutineViewModel, contentPadding: PaddingValues) {
    val days by vm.routineDays.collectAsStateWithLifecycle()
    val templates by vm.templateList.collectAsStateWithLifecycle()
    val names by vm.exerciseNames.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Routine", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item {
            SectionCard("Each weekday") {
                Hint("This plan shows on every matching day. Changes only reach days that aren't locked yet (10pm the night before).")
                days.forEach { d -> RoutineRow(d, templates, vm) }
            }
        }
        item {
            SectionCard("Workouts") {
                if (templates.isEmpty()) Hint("No workouts yet. Add one below, then assign it to weekdays above.")
                templates.forEach { t -> TemplateEditor(t, names, vm) }
                var name by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        name, { name = it }, Modifier.weight(1f), placeholder = { Text("New workout, e.g. Push A") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.addTemplate(name); name = "" }),
                    )
                    OutlinedButton(onClick = { vm.addTemplate(name); name = "" }) { Text("Add") }
                }
            }
        }
    }
}

@Composable
private fun RoutineRow(d: RoutineDayEntity, templates: List<Template>, vm: RoutineViewModel) {
    var picking by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val name = DayOfWeek.of(d.weekday).getDisplayName(TextStyle.SHORT, Locale.getDefault())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.width(44.dp), fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = { picking = true }) { Text(d.alarmTime, fontFamily = FontFamily.Monospace) }
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { open = true }) { Text(templates.find { it.id == d.templateId }?.name ?: "Rest") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("Rest") }, onClick = { open = false; vm.setRoutineTemplate(d.weekday, null) })
                templates.forEach { t ->
                    DropdownMenuItem(text = { Text(t.name) }, onClick = { open = false; vm.setRoutineTemplate(d.weekday, t.id) })
                }
            }
        }
    }
    if (picking) TimePickerDialog(d.alarmTime, onDismiss = { picking = false }) { vm.setRoutineAlarm(d.weekday, it); picking = false }
}

@Composable
private fun TemplateEditor(t: Template, names: List<String>, vm: RoutineViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    val list = t.exercises
    // Every change saves the whole ordered list; already-logged workouts keep their own copy.
    fun save(next: List<String>) = vm.setTemplateExercises(t.id, next)
    fun move(i: Int, by: Int) = save(list.toMutableList().apply { add(i + by, removeAt(i)) })

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
        }
        if (list.isEmpty()) Hint("No exercises yet.")
        list.forEachIndexed { i, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(24.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, Modifier.weight(1f))
                SmallButton("↑", enabled = i > 0) { move(i, -1) }
                SmallButton("↓", enabled = i < list.lastIndex) { move(i, 1) }
                SmallButton("×") { save(list - name) }
            }
        }
        ExerciseInput(names, onAdd = { save(list + it) }, Modifier.padding(top = 4.dp))
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete ${t.name}?", "Weekdays using it become rest days. Workouts already logged keep their data.", "Delete",
            onDismiss = { confirmDelete = false },
        ) { vm.deleteTemplate(t.id); confirmDelete = false }
    }
}

/** Compact square text button for list rows, so the row's label keeps most of the width. */
@Composable
private fun SmallButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text(label) }
}
