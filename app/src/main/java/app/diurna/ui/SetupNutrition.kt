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
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.diurna.data.SettingsStore.ProfileDraft
import app.diurna.domain.Activity
import app.diurna.domain.Goal
import app.diurna.domain.Sex
import java.time.Instant
import java.time.LocalDate
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
    val lastFour by vm.apiKeyLastFour.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    SectionCard("Food estimates", chip = { if (lastFour != null) Chip("Key set", Tone.Good) else Chip("No key", Tone.Warn) }) {
        Hint("Each food entry is sent once to Claude (claude-haiku-5-5) for a calorie range and macros, when you log it or change its text. Only the entry text is sent.")
        if (lastFour != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Key ending ••••$lastFour", Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                TextButton(onClick = { confirmRemove = true }) { Text("Remove") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    key, { key = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("sk-ant-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Button(onClick = { vm.setApiKey(key); key = "" }, enabled = key.isNotBlank()) { Text("Save") }
            }
        }
        Hint("Use a key from its own Anthropic workspace with a monthly spend limit, so a leaked key can only cost that much. It's stored encrypted on this phone and left out of backups.")
    }
    if (confirmRemove) {
        ConfirmDialog(
            "Remove API key?", "New food entries won't be estimated until you add a key again.", "Remove",
            onDismiss = { confirmRemove = false },
        ) { vm.clearApiKey(); confirmRemove = false }
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
    SectionCard("Backups") {
        Hint("Diurna backs itself up once a day (the last 14 are kept) to its folder on this phone, where a computer can copy them with scripts/backup-db.")
        Text(
            last?.let { "Last backup: " + it.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm")) } ?: "No backup yet",
            fontFamily = FontFamily.Monospace,
        )
        OutlinedButton(onClick = vm::backupNow) { Text("Back up now") }
    }
}
