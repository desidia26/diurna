package app.diurna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.data.DailyMetricRow
import app.diurna.data.LibraryRepository.Result
import app.diurna.domain.DailyMetrics
import app.diurna.domain.DailyMetrics.Kind
import app.diurna.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

@Composable
fun CheckInSetupCard(vm: SetupViewModel, onOpen: () -> Unit) {
    val rows by vm.dailyMetrics.collectAsStateWithLifecycle()
    val active = rows.count { !it.def.archived }
    SectionCard("Check-in") {
        Hint("What the Day screen's evening Check-in asks for: mood and energy to start, plus anything else you want to track daily.")
        OutlinedButton(onClick = onOpen) { Text("Daily metrics ($active)") }
    }
}

private fun DailyMetricRow.detail(): String = listOfNotNull(
    when (def.kind) {
        Kind.SCALE -> "scale 1–${def.scaleMax}"
        Kind.NUMBER -> "number" + (def.unit?.let { " ($it)" } ?: "")
        Kind.YES_NO -> "yes / no"
        Kind.TEXT -> "text"
    },
    "archived".takeIf { def.archived },
    if (days > 0) "logged on $days ${if (days == 1) "day" else "days"}" else "nothing logged",
).joinToString(" · ")

@Composable
fun DailyMetricsPage(vm: SetupViewModel, onBack: () -> Unit) {
    val rows by vm.dailyMetrics.collectAsStateWithLifecycle()
    // Null: closed. A row with id 0: a new metric.
    var editing by remember { mutableStateOf<DailyMetricRow?>(null) }
    var deleting by remember { mutableStateOf<DailyMetricRow?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageHeader("Daily metrics", onBack) }
        item {
            Hint(
                "Each one is a row on the Day screen's Check-in card and a chart on Metrics. The 9pm reminder waits until all of them are filled in; text ones are optional. " +
                    "Once a metric has values its type and steps stay fixed, so old days mean what they meant. Archive one to take it off the Day screen and keep its history."
            )
        }
        item {
            OutlinedButton(onClick = { editing = DailyMetricRow(DailyMetrics.Def(0, "", Kind.SCALE), days = 0) }) { Text("New metric") }
        }
        itemsIndexed(rows, key = { _, r -> r.def.id }) { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.def.name, color = if (r.def.archived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Hint(r.detail())
                }
                MoveButton("↑", enabled = i > 0) { vm.moveMetric(r.def.id, up = true) }
                MoveButton("↓", enabled = i < rows.lastIndex) { vm.moveMetric(r.def.id, up = false) }
                TextButton(onClick = { editing = r }, contentPadding = PaddingValues(6.dp)) { Text("Edit") }
            }
        }
    }
    editing?.let { r ->
        MetricEditor(
            r,
            onDismiss = { editing = null },
            onSave = { if (it.id == 0L) vm.addMetric(it) else vm.updateMetric(it) },
            onArchive = { vm.setMetricArchived(r.def.id, !r.def.archived); editing = null },
            onDelete = { deleting = r; editing = null },
        )
    }
    deleting?.let { r ->
        ConfirmDialog(
            "Delete ${r.def.name}?",
            if (r.days > 0) "This also deletes its values on ${r.days} ${if (r.days == 1) "day" else "days"}, and can't be undone. Archive keeps them."
            else "Nothing has been logged for it yet.",
            "Delete",
            onDismiss = { deleting = null },
        ) { vm.deleteMetric(r.def.id); deleting = null }
    }
}

@Composable
private fun MoveButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) { Text(label) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricEditor(
    row: DailyMetricRow,
    onDismiss: () -> Unit,
    onSave: suspend (DailyMetrics.Def) -> Result,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val isNew = row.def.id == 0L
    // Values logged: changing the type or steps would change what old days mean.
    val locked = row.days > 0
    var name by remember { mutableStateOf(row.def.name) }
    var kind by remember { mutableStateOf(row.def.kind) }
    var steps by remember { mutableStateOf(row.def.scaleMax) }
    var words by remember { mutableStateOf(List(row.def.scaleMax) { row.def.words.getOrElse(it) { "" } }) }
    var unit by remember { mutableStateOf(row.def.unit ?: "") }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New metric" else "Edit metric") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    name, { name = it; problem = null }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Text("Type", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Kind.entries.forEach { k ->
                        FilterChip(k == kind, { kind = k; problem = null }, label = { Text(k.label) }, enabled = !locked || k == kind)
                    }
                }
                Hint(
                    when (kind) {
                        Kind.SCALE -> "Tap a step, like mood from 1 to 5."
                        Kind.NUMBER -> "Any number, like hours slept (7.5) or glasses of water."
                        Kind.YES_NO -> "Did it happen today, like taking meds or meditating."
                        Kind.TEXT -> "A few words or more, like what went well. Optional in the reminder."
                    }
                )
                if (kind == Kind.SCALE) {
                    Text("Steps", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DailyMetrics.SCALE_STEPS.forEach { n ->
                            FilterChip(n == steps, {
                                steps = n
                                words = List(n) { words.getOrElse(it) { "" } }
                                problem = null
                            }, label = { Text("1–$n") }, enabled = !locked || n == steps)
                        }
                    }
                    Text("A word per step (optional)", fontWeight = FontWeight.SemiBold)
                    words.forEachIndexed { i, w ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${i + 1}", Modifier.width(20.dp))
                            OutlinedTextField(
                                w, { v -> words = words.toMutableList().also { it[i] = v.replace("\n", "") }; problem = null },
                                Modifier.fillMaxWidth(), singleLine = true,
                            )
                        }
                    }
                }
                if (kind == Kind.NUMBER) {
                    OutlinedTextField(
                        unit, { unit = it; problem = null }, Modifier.fillMaxWidth(), label = { Text("Unit (optional)") },
                        placeholder = { Text("h, mg, glasses") }, singleLine = true,
                    )
                }
                if (locked) Hint("Type and steps are fixed: it's logged on ${row.days} ${if (row.days == 1) "day" else "days"}.")
                if (!isNew) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onArchive) { Text(if (row.def.archived) "Restore" else "Archive") }
                        TextButton(onClick = onDelete) { Text("Delete", color = LocalStatusColors.current.warn) }
                    }
                }
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val def = row.def.copy(
                        name = name, kind = kind, scaleMax = steps,
                        // All blank means numbers alone; a partial set keeps blanks where steps have no word.
                        words = words.takeIf { w -> w.any { it.isNotBlank() } } ?: emptyList(),
                        unit = unit,
                    )
                    when (val r = onSave(def)) {
                        Result.Ok -> onDismiss()
                        is Result.Problem -> problem = r.message
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
