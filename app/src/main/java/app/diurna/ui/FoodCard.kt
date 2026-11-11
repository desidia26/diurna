package app.diurna.ui

import app.diurna.domain.FoodLine
import app.diurna.data.totals
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.key
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
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
import app.diurna.domain.ArmedState
import app.diurna.data.ExerciseSetEntity
import app.diurna.domain.AlarmRules
import app.diurna.domain.DayText
import java.time.Instant
import java.time.ZoneId

/** Food: log by text (AI estimate, optionally with a photo) or by picking a saved meal; totals against targets. */
@Composable
fun FoodCard(s: DayUi, vm: DayViewModel) {
    var text by remember { mutableStateOf("") }
    // Photos attached to the entry being typed; saveable so a trip to the camera can't lose them.
    var photos by rememberSaveable { mutableStateOf(listOf<String>()) }
    val attacher = rememberPhotoAttacher(vm) { new -> photos = (photos + new).distinct().take(5) }
    fun log() {
        if (text.isBlank()) return // the text is the entry; a photo only adds to it
        vm.addFood(text, photos)
        text = ""
        photos = emptyList()
    }
    // A saved meal or a scan logged while photos are attached keeps them: pictures of that food.
    fun logMeal(id: Long, servings: Double = 1.0) {
        vm.logMeal(id, servings, photos)
        text = ""
        photos = emptyList()
    }
    var editing by remember { mutableStateOf<FoodEntryEntity?>(null) }
    var servingsFor by remember { mutableStateOf<FoodEntryEntity?>(null) }
    var savingAsMeal by remember { mutableStateOf<FoodEntryEntity?>(null) }
    // Barcode flow: scanning → (new product: check and save) → how many servings → logged.
    var scanning by remember { mutableStateOf(false) }
    // Meal from photo: the packages in one photo → confirmed amounts → one entry today.
    var mealFromPhoto by remember { mutableStateOf(false) }
    var scannedDraft by remember { mutableStateOf<MealEntity?>(null) }
    var servingsOf by remember { mutableStateOf<MealEntity?>(null) }
    val hidden by vm.hiddenFood.collectAsStateWithLifecycle()
    val undoable by vm.undoableFood.collectAsStateWithLifecycle()
    // An entry waiting out its Undo window is already gone from the card and its totals.
    val food = remember(s.food, hidden) { s.food.filter { it.id !in hidden } }
    val totals = remember(food) { food.totals() }
    val n = food.size
    val summary = DayText.nutrition(totals, s.targets) ?: DayText.food(food.map { it.time })
    val meals by vm.meals.collectAsStateWithLifecycle()
    val detailed by vm.detailedNutrition.collectAsStateWithLifecycle()
    val matches = remember(text, meals) {
        suggest(text, meals.map { it.name }, limit = 4).mapNotNull { n -> meals.find { it.name == n } }
    }
    var focused by remember { mutableStateOf(false) }
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    SectionCard("Food", chip = { Chip("$n ${if (n == 1) "entry" else "entries"}") }, fold = rememberFold(vm, "food", summary)) {
        if (s.targets != null) IntakeSummary(totals, s.targets)
        else if (totals.estimated > 0) Hint(summary)
      Column(Modifier.keepVisibleWhileTyping(focused, matches.size), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var attachOpen by remember { mutableStateOf(false) }
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            // The photo hint lives in the placeholder rather than on a line of its own.
            placeholder = { Text(if (photos.isEmpty()) "What did you eat?" else "Add a few words, e.g. \"burrito bowl\"") },
            leadingIcon = {
                Box {
                    IconButton(onClick = { attachOpen = true }, enabled = !attacher.busy) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "Add photo or scan barcode", tint = MaterialTheme.colorScheme.primary)
                    }
                    AttachMenu(attachOpen, attacher, onClose = { attachOpen = false }, onScan = { scanning = true }, allowPhotos = photos.size < 5,
                        onMealFromPhoto = { mealFromPhoto = true })
                }
            },
            trailingIcon = {
                IconButton(onClick = { log() }, enabled = text.isNotBlank()) {
                    Icon(
                        painterResource(R.drawable.ic_send), contentDescription = "Log",
                        tint = if (text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            shape = RoundedCornerShape(28.dp),
            singleLine = true, // so the keyboard's action key logs the entry instead of adding a line
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { log() }),
        )
        PhotoGalleryEditor(vm.photos, photos, attacher, showAdd = false, onChange = { next ->
            photos.filter { it !in next }.forEach(vm::discardPhoto)
            photos = next
        })
        if (photos.isNotEmpty() && !vm.supportsImages()) Hint("This model can't read photos; the estimate will use the text only.")
        // Saved meals matching what's typed: a tap logs exact numbers, no AI.
        if (matches.isNotEmpty()) {
            Text("Saved meals · exact numbers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        if (totals.failed > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${totals.failed} couldn't be estimated", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn,
                )
                TextButton(onClick = vm::retryEstimates) { Text("Retry") }
            }
        }
        if (food.isEmpty()) Hint("Nothing logged yet.")
        val online by vm.online.collectAsStateWithLifecycle()
        Column {
            food.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                key(f.id) {
                    val gallery by vm.entryPhotos(f.id).collectAsStateWithLifecycle(emptyList())
                    SwipeToDelete(onDelete = { vm.deleteFood(f.id) }) {
                        FoodRow(
                            f, s.date, online, vm.photos, detailed, gallery,
                            expanded = expandedId == f.id,
                            onToggle = { expandedId = if (expandedId == f.id) null else f.id },
                            onEdit = { editing = f }, onDelete = { vm.deleteFood(f.id) },
                            onServings = { servingsFor = f }, onSaveAsMeal = { savingAsMeal = f },
                        )
                    }
                }
            }
        }
        undoable?.let { id -> s.food.find { it.id == id } }?.let { gone ->
            UndoBar("Deleted \u201c${gone.text.lines().first()}\u201d", onUndo = vm::undoDeleteFood)
        }
    }
    if (mealFromPhoto) {
        MealFromPhotoFlow(
            scan = vm::scanBarcode,
            onDismiss = { mealFromPhoto = false },
            onLog = { name, lines, save -> vm.logMealFromPhoto(name, lines, save).also { if (it == LibraryRepository.Result.Ok) mealFromPhoto = false } },
        )
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
        MealDialog("Add from barcode", draft, detailed = detailed, labelHost = vm, onDismiss = { scannedDraft = null }, onSave = { meal ->
            vm.saveMeal(meal).also { r ->
                if (r == LibraryRepository.Result.Ok) servingsOf = vm.mealByBarcode(draft.barcode!!)
            }
        })
    }
    servingsOf?.let { m ->
        ServingsDialog(
            1.0,
            title = "${m.name} · ${m.kcal} kcal each",
            cookedWeightG = m.cookedWeightG,
            onDismiss = { servingsOf = null },
            onPick = { logMeal(m.id, it); servingsOf = null },
            onPickGrams = { g -> vm.logMealByGrams(m.id, g, photos); text = ""; photos = emptyList(); servingsOf = null },
        )
    }
    servingsFor?.let { f ->
        ServingsDialog(f.servings, onDismiss = { servingsFor = null }, onPick = { vm.setServings(f.id, it); servingsFor = null })
    }
    savingAsMeal?.let { f ->
        // Pre-filled from the estimate; correct it from the label once and it's exact from then on.
        val seed = MealEntity(
            name = f.text.lines().first().trim().replaceFirstChar { it.uppercase() },
            kcal = f.kcalMid ?: 0, proteinG = f.proteinG ?: 0, carbsG = f.carbsG ?: 0, fatG = f.fatG ?: 0,
            barcode = f.barcode, offSnapshotJson = f.offSnapshotJson,
            fiberG = f.fiberG, solubleFiberG = f.solubleFiberG, insolubleFiberG = f.insolubleFiberG,
            sugarsG = f.sugarsG, addedSugarsG = f.addedSugarsG, starchG = f.starchG,
            satFatG = f.satFatG, monoFatG = f.monoFatG, polyFatG = f.polyFatG, transFatG = f.transFatG,
            cholesterolMg = f.cholesterolMg, omega3G = f.omega3G, omega6G = f.omega6G, omega9G = f.omega9G,
            hisMg = f.hisMg, ileMg = f.ileMg, leuMg = f.leuMg, lysMg = f.lysMg, metMg = f.metMg,
            pheMg = f.pheMg, thrMg = f.thrMg, trpMg = f.trpMg, valMg = f.valMg, cysMg = f.cysMg, tyrMg = f.tyrMg,
            ingredientsJson = f.ingredientsJson, aminoMatched = f.aminoMatched, aminoTotal = f.aminoTotal,
        )
        MealDialog("Save as meal", seed, detailed = detailed, labelHost = vm, onDismiss = { savingAsMeal = null }, onSave = { vm.saveEntryAsMeal(f.id, it) })
    }
    editing?.let { f ->
        val gallery by vm.entryPhotos(f.id).collectAsStateWithLifecycle(emptyList())
        FoodEditDialog(f, vm, initialPhotos = gallery.ifEmpty { listOfNotNull(f.photo) }, onDismiss = { editing = null }) { newText, newTime, newPhotos ->
            vm.editFood(f.id, newText, newTime, newPhotos)
            editing = null
        }
    }
}

/** Intake against targets: calories large with what's left, then protein, carbs and fat side by side. */
@Composable
private fun IntakeSummary(t: NutritionTotals, targets: Targets) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "%,d".format(t.kcal), Modifier.alignByBaseline(),
                style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
            )
            Text(
                "of %,d kcal · %s".format(targets.kcal, FoodLine.kcalLeft(t.kcal, targets.kcal)), Modifier.alignByBaseline(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IntakeBar(t.kcal, targets.kcal, 6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                Triple("Protein", t.proteinG, targets.proteinG),
                Triple("Carbs", t.carbsG, targets.carbsG),
                Triple("Fat", t.fatG, targets.fatG),
            ).forEach { (label, value, target) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IntakeBar(value, target, 3.dp)
                    Text("$value/${target}g", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/** Turns orange past the target. */
@Composable
private fun IntakeBar(value: Int, target: Int, height: Dp) {
    val over = target > 0 && value > target
    LinearProgressIndicator(
        progress = { if (target > 0) (value.toFloat() / target).coerceIn(0f, 1f) else 0f },
        modifier = Modifier.fillMaxWidth().height(height),
        color = if (over) LocalStatusColors.current.warn else MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** Swipe an entry left to delete it; the card then offers Undo. */
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(LocalStatusColors.current.warnBg).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Text("Delete", color = LocalStatusColors.current.warn, style = MaterialTheme.typography.labelLarge) }
        },
        enableDismissFromStartToEnd = false,
        onDismiss = { onDelete() },
    ) {
        Box(Modifier.background(MaterialTheme.colorScheme.surface)) { content() }
    }
}

@Composable
private fun UndoBar(message: String, onUndo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onUndo) { Text("Undo") }
    }
}

/**
 * One line per entry: time, name, calories. Tapping opens the rest in place: numbers, the
 * estimate's assumption, photos and the actions.
 */
@Composable
internal fun FoodRow(
    f: FoodEntryEntity, date: LocalDate, online: Boolean, photos: FoodPhotos, detailed: Boolean,
    gallery: List<String>, expanded: Boolean, onToggle: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onServings: () -> Unit, onSaveAsMeal: () -> Unit,
) {
    val loggedOn = Instant.ofEpochMilli(f.loggedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    var detail by remember { mutableStateOf(false) }
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
    val kcal = FoodLine.kcal(f.isMeal, f.kcalLow, f.kcalHigh, estimated = f.estimatedAt != null, failed = f.estimateError != null)
    val thumbs = gallery.ifEmpty { listOfNotNull(f.photo) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .then(if (expanded) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
                .padding(horizontal = if (expanded) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(f.time, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = muted)
            Text(
                // Open, the whole text shows; closed, the first line fits on one.
                if (expanded) f.text else f.text.lines().first(), Modifier.weight(1f),
                maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (expanded) FontWeight.Medium else null,
            )
            if (!expanded && thumbs.isNotEmpty()) PhotoThumb(photos, thumbs.first(), 28.dp, onClick = onToggle)
            when (kcal) {
                is FoodLine.Kcal.Exact -> Text(kcal.text, fontFamily = FontFamily.Monospace)
                is FoodLine.Kcal.Estimate -> Text(kcal.text, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSecondaryContainer)
                FoodLine.Kcal.Failed -> Text("couldn't estimate", style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn)
                FoodLine.Kcal.Pending -> Text(if (online) "estimating…" else "offline", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    f.isMeal -> Text(
                        "${f.kcalLow} kcal · P ${f.proteinG} · C ${f.carbsG} · F ${f.fatG}",
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    )
                    f.estimatedAt != null -> {
                        Text(
                            "${f.kcalLow}–${f.kcalHigh} kcal · P ${f.proteinG} · C ${f.carbsG} · F ${f.fatG}",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        )
                        if (!f.estimateNote.isNullOrBlank()) Hint("Assumed: ${f.estimateNote}")
                    }
                    f.estimateError != null -> Text(f.estimateError, style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn)
                    else -> Hint(if (online) "Estimating…" else "Estimating when you're back online")
                }
                if (loggedOn != date) Chip("Backfilled ${loggedOn.short()}", Tone.Accent)
                if (thumbs.isNotEmpty()) {
                    var viewing by remember { mutableStateOf<String?>(null) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        thumbs.forEach { name -> PhotoThumb(photos, name, 48.dp) { viewing = name } }
                    }
                    viewing?.let { name -> PhotoViewDialog(photos, name) { viewing = null } }
                }
                if (detail) NutritionDetail(f)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowAction("Edit", onEdit)
                    if (f.isMeal) {
                        RowAction("×${f.servings.servingsLabel()} serving${if (f.servings == 1.0) "" else "s"}", onServings)
                    } else if (f.estimatedAt != null) {
                        RowAction("Save as meal", onSaveAsMeal)
                    }
                    if (hasDetail) RowAction(if (detail) "Hide detail" else "Detail") { detail = !detail }
                    RowAction("Delete", onDelete)
                }
            }
        }
    }
}

@Composable
private fun RowAction(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
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
private fun ServingsDialog(
    current: Double,
    onDismiss: () -> Unit,
    title: String = "Servings",
    cookedWeightG: Double? = null,
    onPick: (Double) -> Unit,
    onPickGrams: ((Double) -> Unit)? = null,
) {
    var custom by remember { mutableStateOf("") }
    var grams by remember { mutableStateOf("") }
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
                    custom, { custom = it }, Modifier.fillMaxWidth(), label = { Text("Other servings") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (cookedWeightG != null && cookedWeightG > 0 && onPickGrams != null) {
                    Hint("Or log by cooked weight (batch ${"%.0f".format(cookedWeightG)} g).")
                    OutlinedTextField(
                        grams, { grams = it }, Modifier.fillMaxWidth(), label = { Text("Grams eaten") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
        },
        confirmButton = {
            val g = grams.toDoubleOrNull()
            if (g != null && g > 0 && onPickGrams != null) {
                TextButton(onClick = { onPickGrams(g) }) { Text("Log grams") }
            } else {
                TextButton(enabled = (custom.toDoubleOrNull() ?: 0.0) > 0, onClick = { onPick(custom.toDouble()) }) { Text("Set") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Fix a wrong estimate by describing the food better; the new text is re-estimated. Photos can be
 * added, replaced or removed here too. Photos attached in the dialog but not saved are deleted.
 */
@Composable
private fun FoodEditDialog(
    f: FoodEntryEntity,
    vm: DayViewModel,
    initialPhotos: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>) -> Unit,
) {
    var text by remember { mutableStateOf(f.text) }
    var time by remember { mutableStateOf(f.time) }
    var picking by remember { mutableStateOf(false) }
    var photos by rememberSaveable { mutableStateOf(initialPhotos) }
    // New files from this dialog; existing gallery files are only deleted by the repository on save.
    var drafts by rememberSaveable { mutableStateOf(listOf<String>()) }
    val attacher = rememberPhotoAttacher(vm) { new ->
        drafts = drafts + new
        photos = (photos + new).distinct().take(5)
    }
    fun close(saved: List<String>) {
        drafts.filter { it !in saved }.forEach(vm::discardPhoto)
    }
    AlertDialog(
        onDismissRequest = { close(emptyList()); onDismiss() },
        title = { Text("Edit entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("What you ate") })
                Hint("Add detail to fix the estimate, e.g. \"double chicken, no rice\". Changing the text or photos re-estimates it.")
                OutlinedButton(onClick = { picking = true }) { Text("Time $time", fontFamily = FontFamily.Monospace) }
                PhotoGalleryEditor(vm.photos, photos, attacher, onChange = { next ->
                    // Don't discard existing files here — only draft files that never get saved.
                    photos = next
                })
            }
        },
        confirmButton = {
            TextButton(onClick = { close(photos); onSave(text, time, photos) }, enabled = text.isNotBlank() && !attacher.busy) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { close(emptyList()); onDismiss() }) { Text("Cancel") } },
    )
    if (picking) TimePickerDialog(time, onDismiss = { picking = false }) { time = it; picking = false }
}
