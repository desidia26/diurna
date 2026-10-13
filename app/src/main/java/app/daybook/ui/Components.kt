package app.daybook.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.daybook.domain.suggest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daybook.domain.AlarmRules.Morning
import app.daybook.domain.AlarmRules.MorningResult
import app.daybook.ui.theme.LocalStatusColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

val ShortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
val WeekdayName: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE")
val WeekdayShort: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")

fun LocalDate.short(): String = format(ShortDate)

fun Double.fmt(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

/** Folding state for a [SectionCard]: tapping the header toggles it, and [summary] stands in for the content when folded. */
data class Fold(val collapsed: Boolean, val summary: String, val onToggle: () -> Unit)

@Composable
fun SectionCard(
    title: String,
    chip: (@Composable () -> Unit)? = null,
    fold: Fold? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val header = if (fold != null) Modifier.clickable(onClickLabel = if (fold.collapsed) "Expand" else "Collapse", onClick = fold.onToggle) else Modifier
            Row(header.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (fold != null) {
                    Text(
                        if (fold.collapsed) "▸" else "▾",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                chip?.invoke()
            }
            if (fold?.collapsed == true) {
                Text(
                    fold.summary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            content()
        }
    }
}

enum class Tone { Neutral, Good, Warn, Accent, Sky }

@Composable
fun Chip(text: String, tone: Tone = Tone.Neutral) {
    val s = LocalStatusColors.current
    val c = MaterialTheme.colorScheme
    val (bg: Color, fg: Color) = when (tone) {
        Tone.Neutral -> c.surfaceVariant to c.onSurfaceVariant
        Tone.Good -> s.goodBg to s.good
        Tone.Warn -> s.warnBg to s.warn
        Tone.Accent -> c.primaryContainer to c.onPrimaryContainer
        Tone.Sky -> c.secondaryContainer to c.onSecondaryContainer
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            text, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun MorningChip(result: MorningResult) = when (result.status) {
    Morning.OFF -> Chip("Alarm off")
    Morning.PLANNED -> Chip("Planned")
    Morning.UPCOMING -> Chip("Upcoming")
    Morning.WAITING -> Chip("Not scanned yet", Tone.Accent)
    Morning.MISSED -> Chip("Missed", Tone.Warn)
    Morning.ON_TIME -> Chip("On time", Tone.Good)
    Morning.LATE -> Chip("+${result.minutesLate} min", Tone.Warn)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/**
 * "Add exercise" box with suggestions from the catalog. Picking a suggestion or pressing
 * Done/Add submits; the box then clears for the next one.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ExerciseInput(names: List<String>, onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val matches = remember(text, names) { suggest(text, names) }
    fun submit(value: String) {
        if (value.isNotBlank()) onAdd(value.trim())
        text = ""
    }
    // Suggestions sit inline under the box (a popup had nowhere to go above the keyboard).
    Column(modifier.keepVisibleWhileTyping(focused, matches.size), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                text, { text = it },
                Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                placeholder = { Text("Add exercise") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit(text) }),
            )
            OutlinedButton(onClick = { submit(text) }) { Text("Add") }
        }
        if (focused && matches.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                matches.forEach { m -> SuggestionChip(
                    onClick = { submit(m) }, label = { Text(m) },
                    colors = SuggestionChipDefaults.suggestionChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
                ) }
            }
        }
    }
}

/**
 * Scrolls this block (a text box plus whatever sits under it, like suggestion chips) above the
 * keyboard while [focused]. Waits for the keyboard to finish sliding in, and re-runs when
 * [contentKey] changes, e.g. when the number of suggestions changes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.keepVisibleWhileTyping(focused: Boolean, contentKey: Any?): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(focused, contentKey) {
        if (!focused) return@LaunchedEffect
        snapshotFlow { ime.getBottom(density) }.collectLatest {
            delay(120)
            requester.bringIntoView()
        }
    }
    return bringIntoViewRequester(requester)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initial: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val start = initial?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(6, 30)
    val state = rememberTimePickerState(start.hour, start.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick("%02d:%02d".format(state.hour, state.minute)) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state) },
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
