package app.diurna.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import app.diurna.domain.SetHints
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import app.diurna.R
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.annotation.DrawableRes
import androidx.compose.ui.focus.onFocusChanged
import app.diurna.domain.suggest
import app.diurna.data.MealEntity
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.SuggestionChip
import java.time.LocalDate
import app.diurna.ui.theme.LocalStatusColors
import app.diurna.domain.Targets
import app.diurna.domain.NutritionTotals
import app.diurna.data.FoodEntryEntity
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
import app.diurna.alarm.ArmedState
import app.diurna.data.ExerciseSetEntity
import app.diurna.domain.AlarmRules
import app.diurna.domain.DayText
import app.diurna.domain.Ratings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.height
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
        if (s.date <= s.today) item { CheckInCard(s, vm) }
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
            StepsRow(vm)
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
        // Enter walks weight → reps → next set's weight, across exercises; explicit requesters so
        // focus skips the buttons in between.
        val requesters = remember { mutableMapOf<Pair<Long, Boolean>, FocusRequester>() }
        fun focusOf(setId: Long, reps: Boolean) = requesters.getOrPut(setId to reps) { FocusRequester() }
        val order = s.exercises.flatMap { e -> e.logged.sets.map { it.id } }
        s.exercises.forEachIndexed { i, ex ->
            ExerciseBlock(
                ex, vm, isFirst = i == 0, isLast = i == s.exercises.lastIndex,
                focusOf = ::focusOf,
                nextSet = { id -> order.getOrNull(order.indexOf(id) + 1) },
            )
        }
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
private fun ExerciseBlock(
    ex: ExerciseUi, vm: DayViewModel, isFirst: Boolean, isLast: Boolean,
    focusOf: (setId: Long, reps: Boolean) -> FocusRequester,
    nextSet: (setId: Long) -> Long?,
) {
    val logged = ex.logged
    val last = ex.last
    val sets = logged.sets
    val done = sets.count { it.reps != null }
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    // "+ Set" puts the cursor in the new row once it arrives from the database.
    var focusWhenMoreThan by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(sets.size) {
        val n = focusWhenMoreThan ?: return@LaunchedEffect
        if (sets.size > n) {
            focusWhenMoreThan = null
            runCatching { focusOf(sets.last().id, false).requestFocus() }
        }
    }
    val focusManager = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(logged.exercise.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            if (sets.isNotEmpty()) Chip("$done/${sets.size}", if (done == sets.size) Tone.Good else Tone.Neutral)
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Move up") }, enabled = !isFirst, onClick = { menu = false; vm.moveExercise(logged.logged.id, up = true) })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = !isLast, onClick = { menu = false; vm.moveExercise(logged.logged.id, up = false) })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Remove") }, onClick = {
                        menu = false
                        if (sets.any { it.weight != null || it.reps != null }) confirmRemove = true
                        else vm.removeExercise(logged.logged.id)
                    })
                }
            }
        }
        Hint(
            if (last != null) "Last time, ${last.date.short()}: " + last.sets.joinToString(" · ") { "${it.weight?.fmt() ?: "–"}×${it.reps}" }
            else "First time logging this"
        )
        sets.forEachIndexed { i, set ->
            val next = nextSet(set.id)
            SetRow(
                i + 1, set, SetHints.hint(i, sets, last?.sets.orEmpty()),
                weightFocus = focusOf(set.id, false), repsFocus = focusOf(set.id, true),
                onRepsNext = next?.let { id -> { focusOf(id, false).requestFocus() } },
                onLastDone = { focusManager.clearFocus() },
                onChange = { wt, r -> vm.updateSet(set.id, wt, r) },
                onDelete = { vm.deleteSet(set.id, logged.logged.id) },
            )
        }
        Row {
            TextButton(onClick = { focusWhenMoreThan = sets.size; vm.addSet(logged.logged.id) }) { Text("+ Set") }
            if (last != null) TextButton(onClick = { vm.useLast(logged.logged.id, logged.exercise.id) }) { Text("Same as last") }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            "Remove ${logged.exercise.name}?", "This deletes the sets logged for it today.", "Remove",
            onDismiss = { confirmRemove = false },
        ) { vm.removeExercise(logged.logged.id); confirmRemove = false }
    }
}

/**
 * Weight and reps for one set. Enter moves on (weight → reps → [onRepsNext]); on an empty field it
 * first accepts the grey hint. Tapping a filled field selects it, so typing replaces the value.
 */
@Composable
private fun SetRow(
    n: Int, set: ExerciseSetEntity, hint: SetHints.Hint,
    weightFocus: FocusRequester, repsFocus: FocusRequester,
    onRepsNext: (() -> Unit)?, onLastDone: () -> Unit,
    onChange: (Double?, Int?) -> Unit, onDelete: () -> Unit,
) {
    // Local text keyed by set id only: the database echoes every keystroke back, and a stale echo
    // must not overwrite newer typing. Changes made elsewhere ("Same as last", "+ Set") create new ids.
    var weight by remember(set.id) { mutableStateOf(TextFieldValue(set.weight?.fmt() ?: "")) }
    var reps by remember(set.id) { mutableStateOf(TextFieldValue(set.reps?.toString() ?: "")) }
    fun save() = onChange(weight.text.toDoubleOrNull(), reps.text.toIntOrNull())
    fun acceptWeightHint() {
        if (weight.text.isEmpty() && hint.weight != null) { weight = TextFieldValue(hint.weight.fmt()); save() }
    }
    fun acceptRepsHint() {
        if (reps.text.isEmpty() && hint.reps != null) { reps = TextFieldValue(hint.reps.toString()); save() }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$n", Modifier.width(18.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            weight, { v -> val changed = v.text != weight.text; weight = v; if (changed) save() },
            Modifier.weight(1f).focusRequester(weightFocus).selectAllOnFocus({ weight }, { weight = it }),
            placeholder = { Text(hint.weight?.fmt() ?: "0") }, suffix = { Text("lb") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { acceptWeightHint(); repsFocus.requestFocus() }),
        )
        OutlinedTextField(
            reps, { v -> val changed = v.text != reps.text; reps = v; if (changed) save() },
            Modifier.weight(1f).focusRequester(repsFocus).selectAllOnFocus({ reps }, { reps = it }),
            placeholder = { Text(hint.reps?.toString() ?: "0") }, suffix = { Text("reps") }, singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = if (onRepsNext != null) ImeAction.Next else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onNext = { acceptRepsHint(); onRepsNext?.invoke() },
                onDone = { acceptRepsHint(); onLastDone() },
            ),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Text("×", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Selects the whole value when the field gains focus. Applied twice: a tap places its own cursor
 * just after focus arrives, which would otherwise undo the first selection.
 */
@Composable
private fun Modifier.selectAllOnFocus(value: () -> TextFieldValue, set: (TextFieldValue) -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    return onFocusChanged { f ->
        if (!f.isFocused) return@onFocusChanged
        scope.launch {
            repeat(2) {
                val v = value()
                set(v.copy(selection = TextRange(0, v.text.length)))
                delay(80)
            }
        }
    }
}

@Composable
private fun StepsRow(vm: DayViewModel) {
    val ui by vm.steps.collectAsStateWithLifecycle()
    val st = ui ?: return
    LabeledRow(
        "Steps",
        value = {
            val count = st.count
            if (count == null) {
                Hint("No steps recorded yet")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("%,d / %,d".format(count, st.goal), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { (count.toFloat() / st.goal).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (count >= st.goal) LocalStatusColors.current.good else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        drawStopIndicator = {},
                    )
                }
            }
        },
        trailing = {},
    )
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

@Composable
private fun CheckInCard(s: DayUi, vm: DayViewModel) {
    val mood = s.day?.mood
    val energy = s.day?.energy
    val rated = mood != null && energy != null
    SectionCard(
        "Check-in",
        chip = { if (rated) Chip("Done", Tone.Good) },
        fold = rememberFold(vm, "checkin", Ratings.summary(mood, energy)),
    ) {
        RatingRow("Mood", mood, Ratings.moodWords, vm::setMood)
        RatingRow("Energy", energy, Ratings.energyWords, vm::setEnergy)
    }
}

/** Five tap targets; tapping the chosen one again clears it. */
@Composable
private fun RatingRow(label: String, value: Int?, words: List<String>, onSet: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Ratings.word(words, value) ?: "—", style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { n ->
                val selected = n == value
                Surface(
                    onClick = { onSet(if (selected) null else n) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("$n", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
