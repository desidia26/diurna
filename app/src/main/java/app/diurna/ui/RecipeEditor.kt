package app.diurna.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.diurna.data.LibraryRepository.Result
import app.diurna.data.MealEntity
import app.diurna.data.RecipeIngredientEntity
import app.diurna.data.fdc.FdcSearchHit
import app.diurna.domain.IngredientDensities
import app.diurna.domain.IngredientUnit
import app.diurna.domain.IngredientUnits
import app.diurna.domain.RecipeMath
import app.diurna.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Draft line while editing; [grams] null means volume/each conversion was refused. */
data class IngredientDraft(
    val name: String = "",
    val amount: Double = 0.0,
    val unit: IngredientUnit = IngredientUnit.G,
    val densityId: String? = null,
    val gramsPerEach: Double? = null,
    val grams: Double? = null,
    val nutrition: RecipeMath.IngredientNutrition = RecipeMath.IngredientNutrition(),
    val kcal: Int = 0,
    val source: String? = null,
    val fdcId: Int? = null,
    val barcode: String? = null,
    val sourceMealId: Long? = null,
) {
    fun toEntity(mealId: Long, position: Int) = RecipeIngredientEntity(
        mealId = mealId,
        position = position,
        name = name,
        amount = amount,
        unit = unit.key,
        grams = grams,
        densityId = densityId,
        gramsPerEach = gramsPerEach,
        proteinG = nutrition.proteinG,
        carbsG = nutrition.carbsG,
        fatG = nutrition.fatG,
        kcal = kcal,
        fiberG = nutrition.fiberG,
        solubleFiberG = nutrition.solubleFiberG,
        insolubleFiberG = nutrition.insolubleFiberG,
        sugarsG = nutrition.sugarsG,
        addedSugarsG = nutrition.addedSugarsG,
        starchG = nutrition.starchG,
        satFatG = nutrition.satFatG,
        monoFatG = nutrition.monoFatG,
        polyFatG = nutrition.polyFatG,
        transFatG = nutrition.transFatG,
        cholesterolMg = nutrition.cholesterolMg,
        omega3G = nutrition.omega3G,
        omega6G = nutrition.omega6G,
        omega9G = nutrition.omega9G,
        hisMg = nutrition.aminos.hisMg,
        ileMg = nutrition.aminos.ileMg,
        leuMg = nutrition.aminos.leuMg,
        lysMg = nutrition.aminos.lysMg,
        metMg = nutrition.aminos.metMg,
        pheMg = nutrition.aminos.pheMg,
        thrMg = nutrition.aminos.thrMg,
        trpMg = nutrition.aminos.trpMg,
        valMg = nutrition.aminos.valMg,
        cysMg = nutrition.aminos.cysMg,
        tyrMg = nutrition.aminos.tyrMg,
        source = source,
        fdcId = fdcId,
        barcode = barcode,
        sourceMealId = sourceMealId,
    )
}

fun RecipeIngredientEntity.toDraft() = IngredientDraft(
    name = name,
    amount = amount,
    unit = IngredientUnit.of(unit),
    densityId = densityId,
    gramsPerEach = gramsPerEach,
    grams = grams,
    nutrition = RecipeMath.IngredientNutrition(
        proteinG, carbsG, fatG, fiberG, solubleFiberG, insolubleFiberG,
        sugarsG, addedSugarsG, starchG, satFatG, monoFatG, polyFatG, transFatG,
        cholesterolMg, omega3G, omega6G, omega9G,
        app.diurna.domain.AminoAcids(hisMg, ileMg, leuMg, lysMg, metMg, pheMg, thrMg, trpMg, valMg, cysMg, tyrMg),
    ),
    kcal = kcal,
    source = source,
    fdcId = fdcId,
    barcode = barcode,
    sourceMealId = sourceMealId,
)

/**
 * Recipe editor: name, yield, optional cooked weight, ingredient lines with live per-serving totals.
 * Add ingredients manually, via USDA FDC (when a key is set), barcode (OFF), or a saved library meal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeDialog(
    title: String,
    initial: MealEntity,
    initialLines: List<RecipeIngredientEntity>,
    detailed: Boolean,
    hasFdcKey: Boolean,
    savedMeals: List<MealEntity>,
    searchFdc: suspend (String) -> List<FdcSearchHit>,
    loadFdcNutrition: suspend (Int) -> app.diurna.data.fdc.FdcNutritionProfile?,
    scanBarcode: suspend (String, Boolean) -> app.diurna.data.LibraryRepository.ScanResult,
    onDismiss: () -> Unit,
    onSave: suspend (MealEntity, List<RecipeIngredientEntity>) -> Result,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var yieldStr by remember { mutableStateOf((initial.servingsYield ?: 1.0).let { if (it == 1.0) "1" else it.toString() }) }
    var cookedStr by remember { mutableStateOf(initial.cookedWeightG?.toString() ?: "") }
    var note by remember { mutableStateOf(initial.note ?: "") }
    val lines = remember {
        mutableStateListOf<IngredientDraft>().also { list ->
            initialLines.forEach { list.add(it.toDraft()) }
        }
    }
    var problem by remember { mutableStateOf<String?>(null) }
    var addMode by remember { mutableStateOf<String?>(null) } // manual / fdc / barcode / meal

    val yield = yieldStr.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
    val totals = remember(lines.toList(), yield) {
        RecipeMath.perServing(lines.map { it.nutrition }, yield)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        yieldStr, { yieldStr = it }, Modifier.weight(1f),
                        label = { Text("Servings yield") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        cookedStr, { cookedStr = it }, Modifier.weight(1f),
                        label = { Text("Cooked g (opt)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional)") }, singleLine = true)
                Text(
                    "Per serving: %,d kcal · P %d · C %d · F %d".format(totals.kcal, totals.proteinG, totals.carbsG, totals.fatG),
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                )
                if (detailed && totals.aminoPartial) {
                    Text(
                        "Aminos partial: ${totals.aminoMatched}/${totals.aminoTotal} ingredients",
                        style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn,
                    )
                } else if (detailed && totals.aminoMatched != null && totals.aminoMatched > 0) {
                    Hint("Aminos from ${totals.aminoMatched}/${totals.aminoTotal} ingredients")
                }
                HorizontalDivider()
                Text("Ingredients", fontWeight = FontWeight.SemiBold)
                lines.forEachIndexed { i, line ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                            Text(line.name.ifBlank { "(unnamed)" }, fontWeight = FontWeight.Medium)
                            val g = line.grams?.let { "%.0f g".format(it) } ?: "grams unknown"
                            Text(
                                "${line.amount} ${line.unit.key} · $g · P ${line.nutrition.proteinG} C ${line.nutrition.carbsG} F ${line.nutrition.fatG}",
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            )
                            if (line.grams == null && line.unit.needsDensity) {
                                Text("Needs density", style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn)
                            }
                            if (line.grams == null && line.unit.needsGramsPerEach) {
                                Text("Needs g per each", style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn)
                            }
                        }
                        TextButton(onClick = { lines.removeAt(i) }) { Text("Remove") }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { addMode = "manual" }) { Text("Manual") }
                    if (hasFdcKey) TextButton(onClick = { addMode = "fdc" }) { Text("USDA") }
                    else Hint("Add a USDA FDC key in Setup to search Foundation / SR Legacy.")
                    TextButton(onClick = { addMode = "barcode" }) { Text("Barcode") }
                    TextButton(onClick = { addMode = "meal" }) { Text("Saved item") }
                }
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && lines.isNotEmpty(),
                onClick = {
                    val meal = initial.copy(
                        name = name,
                        kind = MealEntity.KIND_RECIPE,
                        servingsYield = yield,
                        cookedWeightG = cookedStr.toDoubleOrNull()?.takeIf { it > 0 },
                        note = note.ifBlank { null },
                        kcal = totals.kcal,
                        proteinG = totals.proteinG,
                        carbsG = totals.carbsG,
                        fatG = totals.fatG,
                        fiberG = if (detailed || totals.fiberG != null) totals.fiberG else initial.fiberG,
                        solubleFiberG = totals.solubleFiberG,
                        insolubleFiberG = totals.insolubleFiberG,
                        sugarsG = totals.sugarsG,
                        addedSugarsG = totals.addedSugarsG,
                        starchG = totals.starchG,
                        satFatG = totals.satFatG,
                        monoFatG = totals.monoFatG,
                        polyFatG = totals.polyFatG,
                        transFatG = totals.transFatG,
                        cholesterolMg = totals.cholesterolMg,
                        omega3G = totals.omega3G,
                        omega6G = totals.omega6G,
                        omega9G = totals.omega9G,
                        hisMg = totals.aminos.hisMg,
                        ileMg = totals.aminos.ileMg,
                        leuMg = totals.aminos.leuMg,
                        lysMg = totals.aminos.lysMg,
                        metMg = totals.aminos.metMg,
                        pheMg = totals.aminos.pheMg,
                        thrMg = totals.aminos.thrMg,
                        trpMg = totals.aminos.trpMg,
                        valMg = totals.aminos.valMg,
                        cysMg = totals.aminos.cysMg,
                        tyrMg = totals.aminos.tyrMg,
                        aminoMatched = totals.aminoMatched,
                        aminoTotal = totals.aminoTotal,
                    )
                    val entities = lines.mapIndexed { i, d -> d.toEntity(initial.id, i) }
                    scope.launch {
                        when (val r = onSave(meal, entities)) {
                            Result.Ok -> onDismiss()
                            is Result.Problem -> problem = r.message
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    when (addMode) {
        "manual" -> ManualIngredientDialog(
            detailed = detailed,
            onDismiss = { addMode = null },
            onAdd = { lines.add(it); addMode = null },
        )
        "fdc" -> FdcIngredientDialog(
            search = searchFdc,
            load = loadFdcNutrition,
            onDismiss = { addMode = null },
            onAdd = { lines.add(it); addMode = null },
        )
        "barcode" -> BarcodeIngredientDialog(
            scan = scanBarcode,
            onDismiss = { addMode = null },
            onAdd = { lines.add(it); addMode = null },
        )
        "meal" -> SavedMealIngredientDialog(
            meals = savedMeals.filter { it.id != initial.id && !it.isRecipe },
            onDismiss = { addMode = null },
            onAdd = { lines.add(it); addMode = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualIngredientDialog(
    detailed: Boolean,
    onDismiss: () -> Unit,
    onAdd: (IngredientDraft) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(IngredientUnit.G) }
    var densityId by remember { mutableStateOf<String?>(null) }
    var gEach by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var f by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val amount = amountStr.toDoubleOrNull() ?: 0.0
    val density = IngredientDensities.byId(densityId)?.gPerMl
    val grams = IngredientUnits.toGrams(amount, unit, density, gEach.toDoubleOrNull())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual ingredient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    amountStr, { amountStr = it }, Modifier.fillMaxWidth(), label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IngredientUnit.entries.forEach { u ->
                        FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u.label) })
                    }
                }
                if (unit.needsDensity) {
                    Hint("Volume needs a density — we won't assume water.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IngredientDensities.OPTIONS.forEach { d ->
                            FilterChip(
                                selected = densityId == d.id,
                                onClick = { densityId = d.id },
                                label = { Text(d.label.substringBefore(" (")) },
                            )
                        }
                    }
                }
                if (unit.needsGramsPerEach) {
                    OutlinedTextField(
                        gEach, { gEach = it }, Modifier.fillMaxWidth(), label = { Text("Grams per each") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(p, { p = it }, Modifier.weight(1f), label = { Text("P") }, suffix = { Text("g") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(c, { c = it }, Modifier.weight(1f), label = { Text("C") }, suffix = { Text("g") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(f, { f = it }, Modifier.weight(1f), label = { Text("F") }, suffix = { Text("g") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(kcal, { kcal = it }, Modifier.fillMaxWidth(), label = { Text("kcal (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                grams?.let { Hint("Resolved: %.1f g".format(it)) }
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) { problem = "Name required."; return@TextButton }
                if (amount <= 0) { problem = "Amount must be positive."; return@TextButton }
                if (grams == null) {
                    problem = when {
                        unit.needsDensity -> "Pick a density for ${unit.label}."
                        unit.needsGramsPerEach -> "Enter grams per each."
                        else -> "Could not resolve grams."
                    }
                    return@TextButton
                }
                val protein = p.toIntOrNull() ?: 0
                val carbs = c.toIntOrNull() ?: 0
                val fat = f.toIntOrNull() ?: 0
                val k = kcal.toIntOrNull()
                    ?: app.diurna.domain.MacroEnergy.kcal(protein, carbs, fat).total.roundToInt()
                onAdd(
                    IngredientDraft(
                        name = name.trim(),
                        amount = amount,
                        unit = unit,
                        densityId = densityId,
                        gramsPerEach = gEach.toDoubleOrNull(),
                        grams = grams,
                        nutrition = RecipeMath.IngredientNutrition(proteinG = protein, carbsG = carbs, fatG = fat),
                        kcal = k,
                        source = "manual",
                    ),
                )
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FdcIngredientDialog(
    search: suspend (String) -> List<FdcSearchHit>,
    load: suspend (Int) -> app.diurna.data.fdc.FdcNutritionProfile?,
    onDismiss: () -> Unit,
    onAdd: (IngredientDraft) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<FdcSearchHit>>(emptyList()) }
    var amountStr by remember { mutableStateOf("100") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("USDA FoodData Central") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Foundation + SR Legacy. Amounts are per the grams you enter.")
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search") }, singleLine = true)
                OutlinedTextField(
                    amountStr, { amountStr = it }, Modifier.fillMaxWidth(), label = { Text("Grams") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                TextButton(enabled = query.isNotBlank() && !busy, onClick = {
                    busy = true; problem = null
                    scope.launch {
                        hits = search(query)
                        if (hits.isEmpty()) problem = "No results."
                        busy = false
                    }
                }) { Text(if (busy) "Searching…" else "Search") }
                hits.forEach { hit ->
                    Text(
                        hit.description,
                        Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            val grams = amountStr.toDoubleOrNull()
                            if (grams == null || grams <= 0) { problem = "Enter grams."; return@clickable }
                            busy = true; problem = null
                            scope.launch {
                                val profile = load(hit.fdcId)
                                busy = false
                                if (profile == null) { problem = "Couldn't load nutrition."; return@launch }
                                val nut = profile.forGrams(grams)
                                val k = (profile.kcalPer100 * grams / 100.0).roundToInt().coerceAtLeast(0)
                                onAdd(
                                    IngredientDraft(
                                        name = profile.description.ifBlank { hit.description },
                                        amount = grams,
                                        unit = IngredientUnit.G,
                                        grams = grams,
                                        nutrition = nut,
                                        kcal = k,
                                        source = "fdc",
                                        fdcId = hit.fdcId,
                                    ),
                                )
                            }
                        }.padding(vertical = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BarcodeIngredientDialog(
    scan: suspend (String, Boolean) -> app.diurna.data.LibraryRepository.ScanResult,
    onDismiss: () -> Unit,
    onAdd: (IngredientDraft) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Reuse the camera dialog when possible; also allow paste for tests / no-camera.
    var code by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("100") }
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Barcode ingredient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Looks up Open Food Facts, then scales to the grams you enter.")
                OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("Barcode") }, singleLine = true)
                OutlinedTextField(
                    amountStr, { amountStr = it }, Modifier.fillMaxWidth(), label = { Text("Grams") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = code.isNotBlank() && !busy, onClick = {
                val grams = amountStr.toDoubleOrNull()
                if (grams == null || grams <= 0) { problem = "Enter grams."; return@TextButton }
                busy = true; problem = null
                scope.launch {
                    when (val r = scan(code, false)) {
                        is app.diurna.data.LibraryRepository.ScanResult.Known -> {
                            val m = r.meal
                            // Treat saved meal macros as one serving; scale by grams/100 as a crude default
                            // when note has no weight — prefer using "Saved item" for library meals.
                            val factor = grams / 100.0
                            onAdd(mealToDraft(m, grams, factor, barcode = m.barcode))
                        }
                        is app.diurna.data.LibraryRepository.ScanResult.Found -> {
                            val m = r.draft
                            val factor = grams / 100.0
                            onAdd(mealToDraft(m, grams, factor, barcode = m.barcode, source = "barcode"))
                        }
                        app.diurna.data.LibraryRepository.ScanResult.NotFound -> problem = "Product not found."
                        is app.diurna.data.LibraryRepository.ScanResult.Failed -> problem = r.message
                    }
                    busy = false
                }
            }) { Text(if (busy) "Looking up…" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SavedMealIngredientDialog(
    meals: List<MealEntity>,
    onDismiss: () -> Unit,
    onAdd: (IngredientDraft) -> Unit,
) {
    var servingsStr by remember { mutableStateOf("1") }
    var selected by remember { mutableStateOf<MealEntity?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("From saved meal") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    servingsStr, { servingsStr = it }, Modifier.fillMaxWidth(), label = { Text("Servings of that meal") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                meals.forEach { m ->
                    Text(
                        "${m.name} · ${m.macroLine()}",
                        Modifier.fillMaxWidth().clickable { selected = m }.padding(vertical = 6.dp),
                        fontWeight = if (selected?.id == m.id) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                if (meals.isEmpty()) Hint("No simple meals in the library yet.")
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = {
                val m = selected!!
                val s = servingsStr.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                onAdd(mealToDraft(m, grams = null, factor = s, sourceMealId = m.id, source = "meal"))
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun mealToDraft(
    m: MealEntity,
    grams: Double?,
    factor: Double,
    barcode: String? = null,
    sourceMealId: Long? = null,
    source: String = "meal",
): IngredientDraft {
    fun s(v: Int) = (v * factor).roundToInt().coerceAtLeast(0)
    fun o(v: Int?) = v?.let { (it * factor).roundToInt().coerceAtLeast(0) }
    val nut = RecipeMath.IngredientNutrition(
        proteinG = s(m.proteinG), carbsG = s(m.carbsG), fatG = s(m.fatG),
        fiberG = o(m.fiberG), solubleFiberG = o(m.solubleFiberG), insolubleFiberG = o(m.insolubleFiberG),
        sugarsG = o(m.sugarsG), addedSugarsG = o(m.addedSugarsG), starchG = o(m.starchG),
        satFatG = o(m.satFatG), monoFatG = o(m.monoFatG), polyFatG = o(m.polyFatG), transFatG = o(m.transFatG),
        cholesterolMg = o(m.cholesterolMg), omega3G = o(m.omega3G), omega6G = o(m.omega6G), omega9G = o(m.omega9G),
        aminos = app.diurna.domain.AminoAcids(
            o(m.hisMg), o(m.ileMg), o(m.leuMg), o(m.lysMg), o(m.metMg), o(m.pheMg),
            o(m.thrMg), o(m.trpMg), o(m.valMg), o(m.cysMg), o(m.tyrMg),
        ),
    )
    // Saved-meal lines carry absolute nutrition for the chosen servings; grams are a stand-in
    // so volume/each conversion checks pass (RecipeMath sums nutrition, not grams).
    val resolvedGrams = grams ?: factor
    return IngredientDraft(
        name = m.name,
        amount = if (grams != null) grams else factor,
        unit = if (grams != null) IngredientUnit.G else IngredientUnit.EACH,
        grams = resolvedGrams,
        gramsPerEach = if (grams != null) null else 1.0,
        nutrition = nut,
        kcal = s(m.kcal),
        source = source,
        barcode = barcode,
        sourceMealId = sourceMealId,
    )
}
