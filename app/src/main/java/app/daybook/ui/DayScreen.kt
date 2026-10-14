package app.daybook.ui

import androidx.compose.foundation.layout.Arrangement
import app.daybook.R
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.annotation.DrawableRes
import androidx.compose.ui.focus.onFocusChanged
import app.daybook.domain.suggest
import app.daybook.data.MealEntity
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.SuggestionChip
import java.time.LocalDate
import app.daybook.ui.theme.LocalStatusColors
import app.daybook.domain.Targets
import app.daybook.domain.NutritionTotals
import app.daybook.data.FoodEntryEntity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daybook.alarm.ArmedState
import app.daybook.data.ExerciseSetEntity
import app.daybook.domain.AlarmRules
import app.daybook.domain.DayText
import java.time.Instant
import java.time.ZoneId

@Composable
fun DayScreen(vm: DayViewModel, contentPadding: PaddingValues) {
    val ui by vm.day.collectAsStateWithLifecycle()
    val armed by vm.armed.collectAsStateWithLifecycle()
    val s = ui ?: return
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (armed == ArmedState.NeedsPorchCode) {
            item { Hint("Alarms aren't armed yet. Print your porch code and scan it once in Setup.") }
        }
        item { DateHeader(eyebrow = s.date.format(WeekdayName) + if (s.date == s.today) " · Today" else "", title = s.date.short(), onPrev = { vm.shift(-1) }, onNext = { vm.shift(1) }) }
        if (s.date != s.today) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { vm.goTo(s.today) }) { Text("Back to today") }
                }
            }
        }
        item { MorningCard(s, vm) }
        item { WorkoutCard(s, vm) }
        item { FoodCard(s, vm) }
    }
}

@Composable
fun DateHeader(eyebrow: String, title: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NavArrow(R.drawable.ic_chevron_left, "Previous", onPrev)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        NavArrow(R.drawable.ic_chevron_right, "Next", onNext)
    }
}

/** Round, roomy arrow button for stepping between days or weeks. */
@Composable
private fun NavArrow(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    OutlinedIconButton(onClick = onClick, modifier = Modifier.size(52.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Icon(painterResource(icon), contentDescription = description, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun LabeledRow(label: String, value: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f)) { value() }
        trailing()
    }
}

@Composable
private fun MorningCard(s: DayUi, vm: DayViewModel) {
    var picking by remember { mutableStateOf<String?>(null) } // "alarm" or "manual"
    val off = s.alarm == AlarmRules.OFF
    val override = s.day?.alarmOverride
    val prev = s.date.minusDays(1)
    val cutoff = "10pm ${prev.format(WeekdayShort)} ${prev.short()}"

    val morningSummary = DayText.morning(s.morning, s.alarm, s.day?.scannedAt)
    SectionCard("Morning", chip = { MorningChip(s.morning) }, fold = rememberFold(vm, "morning", morningSummary)) {
        LabeledRow(
            "Alarm",
            value = { Text(if (off) "Off" else s.alarm, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium) },
            trailing = {
                if (s.locked) Chip("Locked", if (off) Tone.Neutral else Tone.Accent)
                else Row {
                    if (!off) TextButton(onClick = { picking = "alarm" }) { Text("Change") }
                    TextButton(onClick = { vm.setOverride(if (off) null else AlarmRules.OFF) }) { Text(if (off) "Turn on" else "Turn off") }
                }
            },
        )
        val hint = when {
            s.locked && off -> "Turned off before the $cutoff cutoff. Not counted."
            s.locked -> "Locked since $cutoff. Only the porch scan stops it."
            off -> "Off for this day. Editable until $cutoff."
            override != null -> "Changed for this day only. Editable until $cutoff."
            else -> "From your routine. Editable until $cutoff."
        }
        Hint(hint)
        if (!s.locked && override != null && !off) {
            TextButton(onClick = { vm.setOverride(null) }, contentPadding = PaddingValues(0.dp)) { Text("Use routine time") }
        }

        if (s.date <= s.today) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            WeightRow(s, vm)
        }
        if (s.date <= s.today && !off) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            val scanned = s.day?.scannedAt
            val manual = s.day?.morningSource == "manual"
            LabeledRow(
                "Porch scan",
                value = {
                    Text(
                        scanned?.let { if (manual) "$it (manual)" else it } ?: "Not yet",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium,
                    )
                },
                trailing = {
                    when {
                        scanned != null && manual -> TextButton(onClick = vm::clearMorning) { Text("Clear") }
                        // Backfill only: logging today by hand would skip today's alarm.
                        scanned == null && s.date < s.today -> TextButton(onClick = { picking = "manual" }) { Text("Log manually") }
                        else -> {}
                    }
                },
            )
        }
    }

    when (picking) {
        "alarm" -> TimePickerDialog(s.alarm, onDismiss = { picking = null }) { vm.setOverride(it); picking = null }
        "manual" -> TimePickerDialog(s.alarm, onDismiss = { picking = null }) { vm.logManualMorning(it); picking = null }
    }
}

@Composable
private fun TemplatePicker(label: String, s: DayUi, onPick: (Long) -> Unit, onBlank: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            s.templates.forEach { t ->
                DropdownMenuItem(text = { Text(t.name) }, onClick = { open = false; onPick(t.id) })
            }
            if (s.templates.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Blank workout") },
                onClick = { open = false; onBlank() },
            )
        }
    }
}

@Composable
private fun WorkoutCard(s: DayUi, vm: DayViewModel) {
    val planned = s.plannedTemplate
    val w = s.workout
    if (w == null) {
        val summary = if (planned != null) "${planned.exercises.size} exercises planned" else "Rest day"
        SectionCard("Workout", chip = { if (planned != null) Chip(planned.name, Tone.Sky) else Chip("Rest") }, fold = rememberFold(vm, "workout", summary)) {
            if (planned != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    planned.exercises.forEachIndexed { i, e -> Text("${i + 1}. $e") }
                    if (planned.exercises.isEmpty()) Hint("No exercises in this workout yet. Add them in Routine.")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.startWorkout(planned.id) }) { Text("Start logging") }
                    TemplatePicker("Do something else", s, onPick = vm::startWorkout, onBlank = vm::startBlankWorkout)
                }
            } else {
                Hint("Rest day in your routine.")
                TemplatePicker("Log a workout anyway", s, onPick = vm::startWorkout, onBlank = vm::startBlankWorkout)
            }
        }
        return
    }

    var confirmRemove by remember { mutableStateOf(false) }
    val swapped = planned != null && planned.name != w.workout.name
    val volume = s.exercises.sumOf { e -> e.logged.sets.sumOf { (it.weight ?: 0.0) * (it.reps ?: 0) } }
    val setsDone = s.exercises.sumOf { e -> e.logged.sets.count { it.reps != null } }
    val summary = "${s.exercises.size} exercises · $setsDone sets · ${volume.fmt()} lb"
    SectionCard("Workout", chip = { Chip(w.workout.name + if (swapped) " (swapped)" else "", Tone.Sky) }, fold = rememberFold(vm, "workout", summary)) {
        s.exercises.forEach { ex -> ExerciseBlock(ex, vm) }
        val names by vm.exerciseNames.collectAsStateWithLifecycle()
        ExerciseInput(names, onAdd = vm::addExercise)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Hint("${volume.fmt()} lb volume")
            TextButton(onClick = { confirmRemove = true }) { Text("Remove workout") }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            "Remove workout?", "This deletes every set logged for ${w.workout.name} on ${s.date.short()}.", "Remove",
            onDismiss = { confirmRemove = false },
        ) { vm.removeWorkout(); confirmRemove = false }
    }
}

@Composable
private fun ExerciseBlock(ex: ExerciseUi, vm: DayViewModel) {
    val logged = ex.logged
    val last = ex.last
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(logged.exercise.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            if (last != null) TextButton(onClick = { vm.useLast(logged.logged.id, logged.exercise.id) }) { Text("Same as last") }
            TextButton(onClick = { vm.addSet(logged.logged.id) }) { Text("+ Set") }
        }
        Hint(
            if (last != null) "Last time, ${last.date.short()}: " + last.sets.joinToString(" · ") { "${it.weight?.fmt() ?: "–"}×${it.reps}" }
            else "First time logging this"
        )
        logged.sets.forEachIndexed { i, set ->
            val placeholder = last?.sets?.let { it.getOrNull(i) ?: it.lastOrNull() }
            SetRow(i + 1, set, placeholder, onChange = { wt, r -> vm.updateSet(set.id, wt, r) }, onDelete = { vm.deleteSet(set.id, logged.logged.id) })
        }
    }
}

@Composable
private fun SetRow(n: Int, set: ExerciseSetEntity, placeholder: ExerciseSetEntity?, onChange: (Double?, Int?) -> Unit, onDelete: () -> Unit) {
    // Local text keyed by set id only: the database echoes every keystroke back, and a stale echo
    // must not overwrite newer typing. Changes made elsewhere ("Same as last", "+ Set") create new ids.
    var weight by remember(set.id) { mutableStateOf(set.weight?.fmt() ?: "") }
    var reps by remember(set.id) { mutableStateOf(set.reps?.toString() ?: "") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$n", Modifier.width(18.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            weight, { weight = it; onChange(it.toDoubleOrNull(), reps.toIntOrNull()) }, Modifier.weight(1f),
            placeholder = { Text(placeholder?.weight?.fmt() ?: "0") }, suffix = { Text("lb") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        OutlinedTextField(
            reps, { reps = it; onChange(weight.toDoubleOrNull(), it.toIntOrNull()) }, Modifier.weight(1f),
            placeholder = { Text(placeholder?.reps?.toString() ?: "0") }, suffix = { Text("reps") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        TextButton(onClick = onDelete, contentPadding = PaddingValues(4.dp)) { Text("×") }
    }
}

@Composable
private fun WeightRow(s: DayUi, vm: DayViewModel) {
    // Keyed by date only: the saved value echoes back on every keystroke and mustn't fight typing.
    var text by remember(s.date) { mutableStateOf(s.day?.bodyWeight?.fmt() ?: "") }
    LabeledRow(
        "Weight",
        value = {
            OutlinedTextField(
                text, { text = it; vm.setBodyWeight(it.toDoubleOrNull()) },
                Modifier.width(140.dp), singleLine = true, placeholder = { Text("—") }, suffix = { Text("lb") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        trailing = {},
    )
}

@Composable
fun rememberFold(vm: DayViewModel, section: String, summary: String): Fold {
    val folded by vm.folded.collectAsStateWithLifecycle()
    return Fold(section in folded, summary) { vm.toggleFold(section) }
}
