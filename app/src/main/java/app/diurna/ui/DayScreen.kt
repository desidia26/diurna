package app.diurna.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
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
import app.diurna.domain.DailyMetrics
import androidx.compose.foundation.layout.RowScope
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.height
import java.time.Instant
import java.time.ZoneId

@Composable
fun DayScreen(
    vm: DayViewModel, contentPadding: PaddingValues,
    tracked: Set<Long>, onViewMetrics: (exerciseId: Long, cardio: Boolean) -> Unit,
) {
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
        item { WorkoutCard(s, vm, tracked, onViewMetrics) }
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
private fun WorkoutCard(s: DayUi, vm: DayViewModel, tracked: Set<Long>, onViewMetrics: (exerciseId: Long, cardio: Boolean) -> Unit) {
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
    val (cardio, strength) = s.exercises.partition { it.logged.exercise.isCardio }
    val volume = strength.sumOf { e -> e.logged.sets.sumOf { (it.weight ?: 0.0) * (it.reps ?: 0) } }
    val setsDone = strength.sumOf { e -> e.logged.sets.count { it.reps != null } }
    // "3,260 m" or "3.1 mi" (or minutes) across the cardio done today, in the first one's units.
    val cardioTotal = DayText.cardioTotal(cardio.map { e ->
        val done = e.logged.sets.filter { it.isDone }
        DayText.ExerciseLine(
            e.logged.exercise.name, cardio = true, done = done.isNotEmpty(),
            distanceM = done.sumOf { it.distanceM ?: 0 }, durationS = done.sumOf { it.durationS ?: 0.0 },
            style = e.logged.exercise.style,
        )
    }.filter { it.done })
    val summary = if (cardio.isEmpty()) {
        // Lifting only: exactly as before cardio existed.
        "${s.exercises.size} exercises · $setsDone sets · ${volume.fmt()} lb"
    } else {
        listOfNotNull(
            "${s.exercises.size} exercises".takeIf { s.exercises.size != 1 || strength.isNotEmpty() },
            "$setsDone sets · ${volume.fmt()} lb".takeIf { strength.isNotEmpty() },
            cardio.singleOrNull()?.takeIf { strength.isEmpty() }?.let {
                cardioSummary(it.logged.sets, it.logged.exercise.style).ifEmpty { "Not logged yet" }
            },
            cardioTotal?.takeIf { strength.isNotEmpty() || cardio.size > 1 },
        ).joinToString(" · ")
    }
    val title = DayText.workoutTitle(w.workout.name, s.exercises.size, cardio.firstOrNull()?.logged?.exercise?.name, cardio.size)
    SectionCard("Workout", chip = { Chip(title + if (swapped) " (swapped)" else "", Tone.Sky) }, fold = rememberFold(vm, "workout", summary)) {
        // Enter walks weight → reps → next set's weight, across exercises; explicit requesters so
        // focus skips the buttons in between.
        val requesters = remember { mutableMapOf<Pair<Long, Boolean>, FocusRequester>() }
        fun focusOf(setId: Long, reps: Boolean) = requesters.getOrPut(setId to reps) { FocusRequester() }
        // Folded exercises' fields aren't on screen, so the chain skips them.
        val folded by vm.folded.collectAsStateWithLifecycle()
        val order = s.exercises.filter { exerciseFoldKey(it) !in folded && !it.logged.exercise.isCardio }.flatMap { e -> e.logged.sets.map { it.id } }
        s.exercises.forEachIndexed { i, ex ->
            ExerciseBlock(
                ex, vm, isFirst = i == 0, isLast = i == s.exercises.lastIndex,
                collapsed = exerciseFoldKey(ex) in folded,
                focusOf = ::focusOf,
                nextSet = { id -> order.getOrNull(order.indexOf(id) + 1) },
                onViewMetrics = if (ex.logged.exercise.id in tracked) onViewMetrics else null,
            )
        }
        val names by vm.exerciseNames.collectAsStateWithLifecycle()
        ExerciseInput(names, onAdd = vm::addExercise)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Hint(
                listOfNotNull(
                    "${volume.fmt()} lb volume".takeIf { strength.isNotEmpty() || cardio.isEmpty() },
                    cardioTotal?.let { "$it cardio" },
                ).joinToString(" · ")
            )
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

private fun exerciseFoldKey(ex: ExerciseUi) = "exercise:${ex.logged.logged.id}"

@Composable
private fun ExerciseBlock(
    ex: ExerciseUi, vm: DayViewModel, isFirst: Boolean, isLast: Boolean, collapsed: Boolean,
    focusOf: (setId: Long, reps: Boolean) -> FocusRequester,
    nextSet: (setId: Long) -> Long?,
    onViewMetrics: ((exerciseId: Long, cardio: Boolean) -> Unit)?,
) {
    val logged = ex.logged
    val last = ex.last
    val sets = logged.sets
    val isCardio = logged.exercise.isCardio
    val done = sets.count { it.isDone }
    val complete = sets.isNotEmpty() && done == sets.size
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
            // Tapping the name folds the exercise to one line of its sets.
            Row(
                Modifier.weight(1f).clickable(onClickLabel = if (collapsed) "Expand" else "Collapse") { vm.toggleFold(exerciseFoldKey(ex)) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val angle by animateFloatAsState(if (collapsed) -90f else 0f, label = "exercise fold")
                Icon(
                    painterResource(R.drawable.ic_chevron_down), contentDescription = null,
                    Modifier.size(20.dp).rotate(angle), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    logged.exercise.name, fontWeight = FontWeight.SemiBold,
                    color = if (complete) LocalStatusColors.current.good else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (sets.isNotEmpty()) Chip("$done/${sets.size}", if (complete) Tone.Good else Tone.Neutral)
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) {
                    Text("⋮", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Move up") }, enabled = !isFirst, onClick = { menu = false; vm.moveExercise(logged.logged.id, up = true) })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = !isLast, onClick = { menu = false; vm.moveExercise(logged.logged.id, up = false) })
                    // Only offered once Metrics has a session to show; otherwise it would open nothing.
                    onViewMetrics?.let { open ->
                        DropdownMenuItem(text = { Text("View metrics") }, onClick = { menu = false; open(logged.exercise.id, isCardio) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Remove") }, onClick = {
                        menu = false
                        if (sets.any { it.weight != null || it.isDone }) confirmRemove = true
                        else vm.removeExercise(logged.logged.id)
                    })
                }
            }
        }
        if (isCardio) {
            CardioPieces(ex, vm, collapsed)
            return@Column
        }
        if (collapsed) {
            Hint(
                sets.filter { it.reps != null }.joinToString(" · ") { "${it.weight?.fmt() ?: "–"}×${it.reps}" }
                    .ifEmpty { "No sets logged yet" }
            )
            return@Column
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
            "Remove ${logged.exercise.name}?", "This deletes the ${if (isCardio) "pieces" else "sets"} logged for it today.", "Remove",
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
    val ui by vm.checkIn.collectAsStateWithLifecycle()
    val ci = ui ?: return
    // Everything archived: nothing to check in.
    if (ci.metrics.isEmpty()) return
    SectionCard(
        "Check-in",
        chip = { if (ci.done) Chip("Done", Tone.Good) },
        fold = rememberFold(vm, "checkin", ci.summary),
    ) {
        ci.metrics.forEach { m ->
            val v = ci.values[m.id]
            when (m.kind) {
                DailyMetrics.Kind.SCALE -> RatingRow(m, v?.value?.roundToInt()) { vm.setMetric(m.id, it?.toDouble()) }
                DailyMetrics.Kind.NUMBER -> NumberMetricRow(s.date, m, v?.value) { vm.setMetric(m.id, it) }
                DailyMetrics.Kind.YES_NO -> YesNoRow(m, v?.value) { vm.setMetric(m.id, it) }
                DailyMetrics.Kind.TEXT -> TextMetricRow(s.date, m, v?.text) { vm.setMetricText(m.id, it) }
            }
        }
    }
}

@Composable
private fun MetricLabel(label: String, shown: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        shown?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** A tap target per step; tapping the chosen one again clears it. */
@Composable
private fun RatingRow(m: DailyMetrics.Def, value: Int?, onSet: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetricLabel(m.name, DailyMetrics.word(m, value?.toDouble()) ?: "—")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (m.scaleMax > 6) 4.dp else 8.dp)) {
            (1..m.scaleMax).forEach { n -> StepButton("$n", n == value, mono = true) { onSet(if (n == value) null else n) } }
        }
    }
}

@Composable
private fun RowScope.StepButton(label: String, selected: Boolean, mono: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(44.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontFamily = if (mono) FontFamily.Monospace else null, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Yes and No; tapping the chosen one again clears it. */
@Composable
private fun YesNoRow(m: DailyMetrics.Def, value: Double?, onSet: (Double?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetricLabel(m.name, null)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("Yes", value == 1.0) { onSet(if (value == 1.0) null else 1.0) }
            StepButton("No", value == 0.0) { onSet(if (value == 0.0) null else 0.0) }
        }
    }
}

@Composable
private fun NumberMetricRow(date: LocalDate, m: DailyMetrics.Def, value: Double?, onSet: (Double?) -> Unit) {
    // Keyed by date only, like weight: the saved value echoes back on every keystroke and mustn't fight typing.
    var text by remember(date, m.id) { mutableStateOf(value?.let(DailyMetrics::number) ?: "") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            text, { text = it; onSet(it.replace(',', '.').toDoubleOrNull()) },
            Modifier.width(140.dp), singleLine = true, placeholder = { Text("—") },
            suffix = m.unit?.takeIf { it.isNotBlank() }?.let { u -> { Text(u) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
    }
}

@Composable
private fun TextMetricRow(date: LocalDate, m: DailyMetrics.Def, value: String?, onSet: (String) -> Unit) {
    var text by remember(date, m.id) { mutableStateOf(value ?: "") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetricLabel(m.name, null)
        OutlinedTextField(
            text, { if (it.length <= DailyMetrics.TEXT_MAX) { text = it; onSet(it) } },
            Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}
