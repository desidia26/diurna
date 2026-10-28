package app.diurna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.Manifest
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.ai.AiProvider
import app.diurna.ai.DetailBackfill
import app.diurna.ai.BackfillWorkState
import app.diurna.ai.GrokFoodEstimator
import app.diurna.data.DataTransfer
import app.diurna.data.SettingsStore.ProfileDraft
import app.diurna.domain.Activity
import app.diurna.domain.Goal
import app.diurna.domain.Sex
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val BirthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

@Composable
private fun Label(text: String) = Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetsCard(vm: SetupViewModel) {
    val saved by vm.profileDraft.collectAsStateWithLifecycle()
    val today by vm.todayTargets.collectAsStateWithLifecycle()
    // Local edits, reset whenever the saved profile changes.
    var d by remember(saved) { mutableStateOf(saved) }
    var pickingBirth by remember { mutableStateOf(false) }

    SectionCard("Targets") {
        Hint("Daily calories and macros from Mifflin–St Jeor, your activity level and goal. They use your latest weigh-in from the Day screen.")

        Label("Sex")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sex.entries.forEach { sx ->
                FilterChip(d.sex == sx, { d = d.copy(sex = sx) }, label = { Text(sx.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }

        Label("Birth date")
        OutlinedButton(onClick = { pickingBirth = true }) { Text(d.birthDate?.format(BirthFormat) ?: "Set birth date") }

        Label("Height")
        // Re-seed the boxes when the saved profile loads, not on every keystroke.
        key(saved) { HeightFields(d.heightIn) { d = d.copy(heightIn = it) } }

        Label("Activity")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Activity.entries.forEach { a -> FilterChip(d.activity == a, { d = d.copy(activity = a) }, label = { Text(a.label) }) }
        }

        Label("Goal")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Goal.entries.forEach { g ->
                val delta = if (g == Goal.CUSTOM) "" else " " + if (g.kcalDelta > 0) "+${g.kcalDelta}" else if (g.kcalDelta < 0) "−${-g.kcalDelta}" else "±0"
                FilterChip(d.goal == g, { d = d.copy(goal = g) }, label = { Text(g.label + delta) })
            }
        }
        if (d.goal == Goal.CUSTOM) {
            NumberField("Calories vs maintenance", d.customKcalDelta.toString(), suffix = "kcal", signed = true) {
                d = d.copy(customKcalDelta = it?.toInt() ?: 0)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(
                "Protein", d.proteinPerLb?.toString() ?: "", suffix = "g/lb",
                placeholder = (d.goal ?: Goal.MAINTAIN).proteinPerLb.toString(), modifier = Modifier.weight(1f),
            ) { d = d.copy(proteinPerLb = it) }
            NumberField("Fat", d.fatPercent.toString(), suffix = "%", modifier = Modifier.weight(1f)) {
                d = d.copy(fatPercent = it?.toInt()?.coerceIn(10, 60) ?: 25)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.saveProfile(d) }, enabled = d != saved) { Text("Save") }
            if (d != saved) TextButton(onClick = { d = saved }) { Text("Discard changes") }
        }
        val t = today
        if (t != null && d == saved) {
            Text(
                "Today: %,d kcal · P %d g · C %d g · F %d g".format(t.kcal, t.proteinG, t.carbsG, t.fatG),
                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
            )
        } else if (d == saved) {
            Hint("Targets appear once everything above is filled in and you've logged a weight on the Day screen.")
        }
    }

    if (pickingBirth) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (d.birthDate ?: LocalDate.of(1995, 1, 1)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingBirth = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { d = d.copy(birthDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickingBirth = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { pickingBirth = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun HeightFields(heightIn: Double?, onChange: (Double?) -> Unit) {
    val total = heightIn?.toInt()
    var ft by remember { mutableStateOf(total?.div(12)?.toString() ?: "") }
    var inches by remember { mutableStateOf(total?.rem(12)?.toString() ?: "") }
    fun push() {
        val f = ft.toIntOrNull()
        val i = inches.toIntOrNull() ?: 0
        onChange(if (f != null) (f * 12 + i).toDouble() else null)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField("Feet", ft, suffix = "ft", modifier = Modifier.width(110.dp)) { ft = it?.toInt()?.toString() ?: ""; push() }
        NumberField("Inches", inches, suffix = "in", modifier = Modifier.width(110.dp)) { inches = it?.toInt()?.toString() ?: ""; push() }
    }
}

/** Numeric text field that reports the parsed value (null when blank or not a number). */
@Composable
private fun NumberField(
    label: String, value: String, suffix: String, modifier: Modifier = Modifier,
    placeholder: String? = null, signed: Boolean = false, onChange: (Double?) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        text, { text = it; onChange(it.toDoubleOrNull()) }, modifier,
        label = { Text(label) }, suffix = { Text(suffix) }, singleLine = true,
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (signed) KeyboardType.Text else KeyboardType.Decimal),
    )
}

@Composable
fun FoodEstimatesCard(vm: SetupViewModel) {
    val provider by vm.aiProvider.collectAsStateWithLifecycle()
    val lastFour by remember(provider) { vm.apiKeyLastFour(provider) }.collectAsStateWithLifecycle()
    var key by remember(provider) { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    SectionCard("Food estimates", chip = { if (lastFour != null) Chip("Key set", Tone.Good) else Chip("No key", Tone.Warn) }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { p ->
                FilterChip(p == provider, { vm.setAiProvider(p) }, label = { Text(p.label) })
            }
        }
        when (provider) {
            AiProvider.ANTHROPIC -> Hint("Each food entry is sent once to Claude (claude-haiku-5-5) for a calorie range and macros, when you log it or change its text or photo. The text is sent, plus the photo if you attach one; nothing else.")
            AiProvider.XAI -> Hint("Each food entry is sent once to Grok (${GrokFoodEstimator.DEFAULT_MODEL}) for a calorie range and macros, when you log it or change its text or photo. The text is sent, plus the photo if you attach one; nothing else.")
        }
        if (lastFour != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Key ending ••••$lastFour", Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    key, { key = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(provider.keyPlaceholder) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(onClick = { vm.setApiKey(provider, key); key = "" }, enabled = key.isNotBlank()) { Text("Save") }
            }
        }
        when (provider) {
            AiProvider.ANTHROPIC -> Hint("Use a key from its own Anthropic workspace with a monthly spend limit, so a leaked key can only cost that much. It's stored encrypted on this phone and left out of backups.")
            AiProvider.XAI -> Hint("Use a key from its own xAI team with a spending limit, so a leaked key can only cost that much. It's stored encrypted on this phone and left out of backups.")
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            "Remove ${provider.company} API key?", "New food entries won't be estimated until you add a key again.", "Remove",
            onDismiss = { confirmRemove = false },
        ) { vm.clearApiKey(provider); confirmRemove = false }
    }
}


@Composable
fun UsdaFdcCard(vm: SetupViewModel) {
    val lastFour by vm.usdaKeyLastFour.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    SectionCard("USDA FoodData Central", chip = { if (lastFour != null) Chip("Key set", Tone.Good) else Chip("No key", Tone.Warn) }) {
        Hint(
            "With Detailed nutrition on, Diurna looks up amino acids for each estimated entry's ingredients " +
                "on USDA FoodData Central (Foundation and SR Legacy foods). Ingredient names only — no personal data — " +
                "are sent to USDA over HTTPS. Get a free key at https://fdc.nal.usda.gov/api-key-signup"
        )
        if (lastFour != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Key ending ••••$lastFour", Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    key, { key = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("api.data.gov key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(onClick = { vm.setUsdaKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save") }
            }
        }
        Hint("Stored encrypted on this phone and left out of backups. Never share or commit the key.")
    }
    if (confirmRemove) {
        ConfirmDialog(
            "Remove USDA FDC API key?",
            "New estimates won't look up amino acids until you add a key again.",
            "Remove",
            onDismiss = { confirmRemove = false },
        ) { vm.clearUsdaKey(); confirmRemove = false }
    }
}

@Composable
fun StepsCard(vm: SetupViewModel) {
    val goal by vm.stepGoal.collectAsStateWithLifecycle()
    val permitted by vm.stepsPermitted.collectAsStateWithLifecycle()
    val today by vm.todaySteps.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshSteps() }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshSteps() }

    SectionCard("Steps", chip = {
        when {
            !vm.stepSensorPresent -> Chip("No sensor", Tone.Warn)
            permitted -> Chip("Counting", Tone.Good)
            else -> Chip("Off", Tone.Warn)
        }
    }) {
        when {
            !vm.stepSensorPresent -> Hint("This phone has no step sensor.")
            !permitted -> {
                Hint("Diurna counts steps with the phone's own step sensor. Android asks for \"Physical activity\" access first.")
                Button(onClick = { request.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text("Allow step counting") }
            }
            else -> {
                Hint("Counted with the phone's step sensor, checked every 15 minutes. Steps only count while the phone is with you.")
                Text(
                    today?.let { "Today: %,d steps".format(it) } ?: "Counting starts from now",
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        NumberField("Daily goal", goal.toString(), suffix = "steps", modifier = Modifier.width(180.dp)) {
            it?.toInt()?.let { g -> if (g >= 1_000) vm.setStepGoal(g) }
        }
    }
}

@Composable
fun BackupsCard(vm: SetupViewModel) {
    val last by vm.lastBackup.collectAsStateWithLifecycle()
    val message by vm.transferMessage.collectAsStateWithLifecycle()
    val staged by vm.stagedImport.collectAsStateWithLifecycle()
    val imported by vm.justImported.collectAsStateWithLifecycle()
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(vm::export)
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::stageImport)
    }
    SectionCard("Backups") {
        if (imported) {
            Text("Imported. API keys aren't part of an export, so add yours again under Food estimates.", color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = vm::dismissImported) { Text("OK") }
        }
        Hint("Diurna backs itself up once a day (the last 14 are kept) to its folder on this phone, where a computer can copy them with scripts/backup-db.")
        Text(
            last?.let { "Last backup: " + it.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm")) } ?: "No backup yet",
            fontFamily = FontFamily.Monospace,
        )
        OutlinedButton(onClick = vm::backupNow) { Text("Back up now") }
        // Backups live in the app's own folder, which uninstalling deletes; an export is saved wherever you pick.
        Hint("To move to a new install, export everything to a file you keep (Downloads, a cloud drive), then import it there. API keys aren't included.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportTo.launch(DataTransfer.fileName()) }) { Text("Export everything") }
            OutlinedButton(onClick = { importFrom.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Import…") }
        }
        message?.let { Hint(it) }
    }
    staged?.let { s ->
        val from = runCatching { LocalDateTime.parse(s.manifest.exportedAt).format(DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")) }
            .getOrDefault(s.manifest.exportedAt)
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text("Replace everything?") },
            text = {
                Text(
                    "Everything in Diurna on this phone will be replaced by the export from $from (${s.days} days). " +
                        "What's here now is backed up first. Diurna restarts to finish.",
                )
            },
            confirmButton = { TextButton(onClick = vm::commitImport) { Text("Replace") } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Cancel") } },
        )
    }
}


@Composable
fun DetailBackfillCard(vm: SetupViewModel) {
    val state by vm.backfillState.collectAsStateWithLifecycle()
    val preview by vm.backfillPreview.collectAsStateWithLifecycle()
    val provider by vm.aiProvider.collectAsStateWithLifecycle()
    val usdaFour by vm.usdaKeyLastFour.collectAsStateWithLifecycle()
    var rangeAll by remember { mutableStateOf(vm.backfillRange.from == null) }
    LaunchedEffect(Unit) { vm.refreshBackfillPreview() }

    SectionCard("Fill in detail for past entries") {
        Hint(
            "Re-runs detailed nutrition for entries that are missing sub-macros or amino acids. " +
                "Existing calories and protein/carbs/fat stay exactly as they are — only null detail fields are filled. " +
                "Uses your ${provider.company} API tokens and resends each entry's stored text and photos to that provider. " +
                (if (usdaFour != null) "Ingredient names also go to USDA for amino lookup."
                 else "Ingredient names go to USDA only when a USDA key is set.")
        )
        Label("Date range")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !rangeAll,
                onClick = {
                    rangeAll = false
                    vm.setBackfillRange(DetailBackfill.Range.lastDays(java.time.LocalDate.now()))
                },
                label = { Text("Last 30 days") },
            )
            FilterChip(
                selected = rangeAll,
                onClick = {
                    rangeAll = true
                    vm.setBackfillRange(DetailBackfill.Range.all())
                },
                label = { Text("All") },
            )
        }
        val p = preview
        if (p != null) {
            Text(
                if (p.entryCount == 0) "Nothing to fill in this range."
                else "%d entries · ~%d AI call%s · %d from saved meals".format(
                    p.entryCount, p.aiCalls, if (p.aiCalls == 1) "" else "s", p.mealCount,
                ),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        when (val s = state) {
            is BackfillWorkState.Running -> {
                Text(
                    if (s.total <= 0) "Starting…" else "Progress: ${s.done} of ${s.total}",
                    fontFamily = FontFamily.Monospace,
                )
                OutlinedButton(onClick = vm::cancelBackfill) { Text("Cancel") }
            }
            is BackfillWorkState.Finished -> {
                val sum = s.summary
                Text(
                    "Done: ${sum.filled} filled · ${sum.partialAminos} partial aminos · " +
                        "${sum.needsMealDetail} need meal detail · ${sum.failed} failed",
                    fontFamily = FontFamily.Monospace,
                )
                Button(
                    onClick = {
                        vm.refreshBackfillPreview()
                        vm.startBackfill()
                    },
                    enabled = (preview?.entryCount ?: 0) > 0,
                ) { Text("Run again") }
            }
            is BackfillWorkState.Cancelled -> {
                Text("Canceled.", fontFamily = FontFamily.Monospace)
                Button(
                    onClick = vm::startBackfill,
                    enabled = (preview?.entryCount ?: 0) > 0,
                ) { Text("Fill in detail") }
            }
            else -> {
                Button(
                    onClick = vm::startBackfill,
                    enabled = (preview?.entryCount ?: 0) > 0,
                ) { Text("Fill in detail") }
            }
        }
    }
}
