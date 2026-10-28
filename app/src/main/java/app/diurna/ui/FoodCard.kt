package app.diurna.ui

import app.diurna.R
import app.diurna.data.LibraryRepository
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.saveable.rememberSaveable
import app.diurna.data.FoodPhotos
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
import java.time.Instant
import java.time.ZoneId

/** Food: log by text (AI estimate, optionally with a photo) or by picking a saved meal; totals against targets. */
@Composable
fun FoodCard(s: DayUi, vm: DayViewModel) {
    var text by remember { mutableStateOf("") }
    // A photo attached to the entry being typed; saveable so a trip to the camera can't lose it.
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    val attacher = rememberPhotoAttacher(vm) { new -> vm.discardPhoto(photo); photo = new }
    fun log() {
        if (text.isBlank()) return // the text is the entry; a photo only adds to it
        vm.addFood(text, photo)
        text = ""
        photo = null
    }
    // A saved meal or a scan logged while a photo is attached keeps the photo: it's a picture of that food.
    fun logMeal(id: Long, servings: Double = 1.0) {
        vm.logMeal(id, servings, photo)
        text = ""
        photo = null
    }
    var editing by remember { mutableStateOf<FoodEntryEntity?>(null) }
    var servingsFor by remember { mutableStateOf<FoodEntryEntity?>(null) }
    var savingAsMeal by remember { mutableStateOf<FoodEntryEntity?>(null) }
    // Barcode flow: scanning → (new product: check and save) → how many servings → logged.
    var scanning by remember { mutableStateOf(false) }
    var scannedDraft by remember { mutableStateOf<MealEntity?>(null) }
    var servingsOf by remember { mutableStateOf<MealEntity?>(null) }
    val n = s.food.size
    val totals = s.totals
    val summary = DayText.nutrition(totals, s.targets) ?: DayText.food(s.food.map { it.time })
    val chip = if (s.targets != null && totals.estimated > 0) "%,d / %,d".format(totals.kcal, s.targets.kcal)
        else "$n ${if (n == 1) "entry" else "entries"}"
    val meals by vm.meals.collectAsStateWithLifecycle()
    val detailed by vm.detailedNutrition.collectAsStateWithLifecycle()
    val matches = remember(text, meals) {
        suggest(text, meals.map { it.name }, limit = 4).mapNotNull { n -> meals.find { it.name == n } }
    }
    var focused by remember { mutableStateOf(false) }
    SectionCard("Food", chip = { Chip(chip) }, fold = rememberFold(vm, "food", summary)) {
      Column(Modifier.keepVisibleWhileTyping(focused, matches.size), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                placeholder = { Text("What did you eat? Free text is fine") },
                singleLine = true, // so the keyboard's action key logs the entry instead of adding a line
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { log() }),
            )
            PhotoMenuButton(attacher)
            IconButton(onClick = { scanning = true }) {
                Icon(painterResource(R.drawable.ic_barcode), contentDescription = "Scan barcode", tint = MaterialTheme.colorScheme.primary)
            }
            Button(onClick = { log() }) { Text("Log") }
        }
        AttachedPhoto(vm.photos, photo, attacher, onRemove = { vm.discardPhoto(photo); photo = null })
        if (photo != null && text.isBlank()) Hint("Add a few words too, e.g. \"chicken burrito\"; the photo helps with portions.")
        // Saved meals matching what's typed: a tap logs exact numbers, no AI.
        if (matches.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                matches.forEach { m ->
                    SuggestionChip(
                        onClick = { logMeal(m.id) },
                        label = { Text("${m.name} · ${m.kcal}") },
                        colors = SuggestionChipDefaults.suggestionChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }
      }
        if (s.targets != null) MacroBars(totals, s.targets)
        else if (totals.estimated > 0) Hint(summary)
        if (totals.failed > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${totals.failed} couldn't be estimated", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn,
                )
                TextButton(onClick = vm::retryEstimates) { Text("Retry") }
            }
        }
        if (s.food.isEmpty()) Hint("Nothing logged yet.")
        val online by vm.online.collectAsStateWithLifecycle()
        s.food.forEach { f ->
            FoodRow(
                f, s.date, online, vm.photos, detailed,
                onEdit = { editing = f }, onDelete = { vm.deleteFood(f.id) },
                onServings = { servingsFor = f }, onSaveAsMeal = { savingAsMeal = f },
            )
        }
    }
    if (scanning) {
        BarcodeScanDialog(
            scan = vm::scanBarcode,
            onKnown = { scanning = false; servingsOf = it },
            onFound = { scanning = false; scannedDraft = it },
            onClose = { scanning = false },
        )
    }
    scannedDraft?.let { draft ->
        MealDialog("Add from barcode", draft, detailed = detailed, onDismiss = { scannedDraft = null }) { meal ->
            vm.saveMeal(meal).also { r ->
                if (r == LibraryRepository.Result.Ok) servingsOf = vm.mealByBarcode(draft.barcode!!)
            }
        }
    }
    servingsOf?.let { m ->
        ServingsDialog(1.0, title = "${m.name} · ${m.kcal} kcal each", onDismiss = { servingsOf = null }) {
            logMeal(m.id, it); servingsOf = null
        }
    }
    servingsFor?.let { f ->
        ServingsDialog(f.servings, onDismiss = { servingsFor = null }) { vm.setServings(f.id, it); servingsFor = null }
    }
    savingAsMeal?.let { f ->
        // Pre-filled from the estimate; correct it from the label once and it's exact from then on.
        val seed = MealEntity(
            name = f.text.lines().first().trim().replaceFirstChar { it.uppercase() },
            kcal = f.kcalMid ?: 0, proteinG = f.proteinG ?: 0, carbsG = f.carbsG ?: 0, fatG = f.fatG ?: 0,
            fiberG = f.fiberG, solubleFiberG = f.solubleFiberG, insolubleFiberG = f.insolubleFiberG,
            sugarsG = f.sugarsG, addedSugarsG = f.addedSugarsG, starchG = f.starchG,
            satFatG = f.satFatG, monoFatG = f.monoFatG, polyFatG = f.polyFatG, transFatG = f.transFatG,
            cholesterolMg = f.cholesterolMg, omega3G = f.omega3G, omega6G = f.omega6G, omega9G = f.omega9G,
            hisMg = f.hisMg, ileMg = f.ileMg, leuMg = f.leuMg, lysMg = f.lysMg, metMg = f.metMg,
            pheMg = f.pheMg, thrMg = f.thrMg, trpMg = f.trpMg, valMg = f.valMg, cysMg = f.cysMg, tyrMg = f.tyrMg,
            ingredientsJson = f.ingredientsJson, aminoMatched = f.aminoMatched, aminoTotal = f.aminoTotal,
        )
        MealDialog("Save as meal", seed, detailed = detailed, onDismiss = { savingAsMeal = null }) { vm.saveEntryAsMeal(f.id, it) }
    }
    editing?.let { f ->
        FoodEditDialog(f, vm, onDismiss = { editing = null }) { newText, newTime, newPhoto ->
            vm.editFood(f.id, newText, newTime, newPhoto)
            editing = null
        }
    }
}

/** Intake against targets: one bar per number, turning orange past the target. */
@Composable
private fun MacroBars(t: NutritionTotals, targets: Targets) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Triple("kcal", t.kcal, targets.kcal),
            Triple("Protein", t.proteinG, targets.proteinG),
            Triple("Carbs", t.carbsG, targets.carbsG),
            Triple("Fat", t.fatG, targets.fatG),
        ).forEach { (label, value, target) ->
            val over = target > 0 && value > target
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(label, Modifier.width(60.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(
                    progress = { if (target > 0) (value.toFloat() / target).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.weight(1f),
                    color = if (over) LocalStatusColors.current.warn else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
                Text(
                    if (label == "kcal") "%,d / %,d".format(value, target) else "$value / ${target}g",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun FoodRow(
    f: FoodEntryEntity, date: LocalDate, online: Boolean, photos: FoodPhotos, detailed: Boolean,
    onEdit: () -> Unit, onDelete: () -> Unit, onServings: () -> Unit, onSaveAsMeal: () -> Unit,
) {
    val loggedOn = Instant.ofEpochMilli(f.loggedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    var expanded by remember { mutableStateOf(false) }
    val hasDetail = detailed && f.estimatedAt != null && (
        f.fiberG != null || f.sugarsG != null || f.addedSugarsG != null || f.starchG != null ||
            f.satFatG != null || f.monoFatG != null || f.polyFatG != null || f.transFatG != null ||
            f.cholesterolMg != null || f.omega3G != null || f.omega6G != null || f.omega9G != null ||
            f.solubleFiberG != null || f.insolubleFiberG != null ||
            f.hisMg != null || f.ileMg != null || f.leuMg != null || f.lysMg != null ||
            f.metMg != null || f.pheMg != null || f.thrMg != null || f.trpMg != null ||
            f.valMg != null || f.cysMg != null || f.tyrMg != null ||
            !f.ingredientsJson.isNullOrBlank()
        )
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Edit", onClick = onEdit),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(f.time, Modifier.padding(top = 2.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(f.text)
            when {
                f.isMeal -> {
                    // Exact numbers from a saved meal: a single value, not a range.
                    Text(
                        "${f.kcalLow} kcal · P ${f.proteinG} · C ${f.carbsG} · F ${f.fatG}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    )
                    val label = f.servings.servingsLabel()
                    TextButton(onClick = onServings, contentPadding = PaddingValues(0.dp)) {
                        Text("×$label serving${if (f.servings == 1.0) "" else "s"}")
                    }
                }
                f.estimatedAt != null -> {
                    Text(
                        "${f.kcalLow}–${f.kcalHigh} kcal · P ${f.proteinG} · C ${f.carbsG} · F ${f.fatG}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    )
                    if (!f.estimateNote.isNullOrBlank()) Hint("Assumed: ${f.estimateNote}")
                    TextButton(onClick = onSaveAsMeal, contentPadding = PaddingValues(0.dp)) { Text("Save as meal") }
                }
                f.estimateError != null -> Text(f.estimateError, style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn)
                else -> Hint(if (online) "Estimating…" else "Estimating when you're back online")
            }
            if (hasDetail) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (expanded) "Hide detail" else "Show detail")
                }
                if (expanded) NutritionDetail(f)
            }
            if (loggedOn != date) Chip("Backfilled ${loggedOn.short()}", Tone.Accent)
        }
        f.photo?.let { name ->
            var viewing by remember { mutableStateOf(false) }
            PhotoThumb(photos, name, 48.dp) { viewing = true }
            if (viewing) PhotoViewDialog(photos, name) { viewing = false }
        }
        TextButton(onClick = onDelete, contentPadding = PaddingValues(4.dp)) { Text("×") }
    }
}

@Composable
private fun NutritionDetail(f: FoodEntryEntity) {
    fun line(label: String, v: Int?, unit: String = "g") =
        v?.let { "$label $it$unit" }
    val carbs = listOfNotNull(
        line("Fiber", f.fiberG),
        line("Soluble", f.solubleFiberG),
        line("Insoluble", f.insolubleFiberG),
        line("Sugars", f.sugarsG),
        line("Added sugars", f.addedSugarsG),
        line("Starch", f.starchG),
    ).joinToString(" · ")
    val fats = listOfNotNull(
        line("Sat", f.satFatG),
        line("Mono", f.monoFatG),
        line("Poly", f.polyFatG),
        line("Trans", f.transFatG),
        line("Chol", f.cholesterolMg, "mg"),
        line("Ω3", f.omega3G),
        line("Ω6", f.omega6G),
        line("Ω9", f.omega9G),
    ).joinToString(" · ")
    val aminos = listOfNotNull(
        line("His", f.hisMg, "mg"), line("Ile", f.ileMg, "mg"), line("Leu", f.leuMg, "mg"),
        line("Lys", f.lysMg, "mg"), line("Met", f.metMg, "mg"), line("Phe", f.pheMg, "mg"),
        line("Thr", f.thrMg, "mg"), line("Trp", f.trpMg, "mg"), line("Val", f.valMg, "mg"),
        line("Cys", f.cysMg, "mg"), line("Tyr", f.tyrMg, "mg"),
    ).joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (carbs.isNotEmpty()) {
            Text("Carbs  $carbs", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        if (fats.isNotEmpty()) {
            Text("Fats   $fats", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        if (aminos.isNotEmpty()) {
            Text("Aminos $aminos", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        val matched = f.aminoMatched
        val total = f.aminoTotal
        if (matched != null && total != null && total > 0) {
            if (matched < total) {
                val missed = f.ingredientsJson?.let { app.diurna.data.fdc.IngredientsJson.decode(it) }
                    ?.filter { it.fdcId == null }
                    ?.joinToString(", ") { it.name }
                    .orEmpty()
                val missBit = if (missed.isNotEmpty()) " — missed: $missed" else ""
                Hint("Amino acids partial: matched $matched of $total ingredients$missBit")
            } else {
                Hint("Amino acids: matched $matched of $total ingredients")
            }
        }
        f.ingredientsJson?.let { raw ->
            val list = app.diurna.data.fdc.IngredientsJson.decode(raw)
            if (!list.isNullOrEmpty() && (matched == null || total == null)) {
                Hint(
                    "Ingredients: " + list.joinToString("; ") { ing ->
                        val match = ing.matchedDescription?.let { " → $it" } ?: ""
                        "${ing.name} (${"%.0f".format(ing.grams)} g)$match"
                    }
                )
            }
        }
    }
}

fun Double.servingsLabel(): String = when (this) {
    0.5 -> "½"
    1.5 -> "1½"
    else -> if (this % 1.0 == 0.0) toInt().toString() else toString()
}

@Composable
private fun ServingsDialog(current: Double, onDismiss: () -> Unit, title: String = "Servings", onPick: (Double) -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5, 1.0, 1.5, 2.0, 3.0).forEach { v ->
                        FilterChip(selected = v == current, onClick = { onPick(v) }, label = { Text("×${v.servingsLabel()}") })
                    }
                }
                OutlinedTextField(
                    custom, { custom = it }, Modifier.fillMaxWidth(), label = { Text("Other") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = (custom.toDoubleOrNull() ?: 0.0) > 0, onClick = { onPick(custom.toDouble()) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Fix a wrong estimate by describing the food better; the new text is re-estimated. The photo can be
 * added, replaced or removed here too. Photos attached in the dialog but not saved are deleted.
 */
@Composable
private fun FoodEditDialog(f: FoodEntryEntity, vm: DayViewModel, onDismiss: () -> Unit, onSave: (String, String, String?) -> Unit) {
    var text by remember { mutableStateOf(f.text) }
    var time by remember { mutableStateOf(f.time) }
    var picking by remember { mutableStateOf(false) }
    var photo by rememberSaveable { mutableStateOf(f.photo) }
    // New files from this dialog; the entry's own photo is only deleted by the repository on save.
    var drafts by rememberSaveable { mutableStateOf(listOf<String>()) }
    val attacher = rememberPhotoAttacher(vm) { new -> drafts = drafts + new; photo = new }
    fun close(saved: String?) {
        drafts.filter { it != saved }.forEach(vm::discardPhoto)
    }
    AlertDialog(
        onDismissRequest = { close(null); onDismiss() },
        title = { Text("Edit entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("What you ate") })
                Hint("Add detail to fix the estimate, e.g. \"double chicken, no rice\". Changing the text or photo re-estimates it.")
                OutlinedButton(onClick = { picking = true }) { Text("Time $time", fontFamily = FontFamily.Monospace) }
                if (photo == null && !attacher.busy) {
                    Box {
                        var open by remember { mutableStateOf(false) }
                        OutlinedButton(onClick = { open = true }) { Text("Add photo") }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            DropdownMenuItem(text = { Text("Take photo") }, onClick = { open = false; attacher.takePhoto() })
                            DropdownMenuItem(text = { Text("Choose photo") }, onClick = { open = false; attacher.choosePhoto() })
                        }
                    }
                    attacher.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn) }
                } else {
                    AttachedPhoto(vm.photos, photo, attacher, onRemove = { photo = null })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { close(photo); onSave(text, time, photo) }, enabled = text.isNotBlank() && !attacher.busy) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { close(null); onDismiss() }) { Text("Cancel") } },
    )
    if (picking) TimePickerDialog(time, onDismiss = { picking = false }) { time = it; picking = false }
}
