package app.diurna.ui

import androidx.compose.foundation.clickable
import app.diurna.R
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import app.diurna.domain.DvBasis
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import app.diurna.domain.Cardio
import app.diurna.domain.ExerciseNote
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.data.CatalogExercise
import app.diurna.data.LibraryRepository.Result
import app.diurna.data.MealEntity
import app.diurna.data.RecipeIngredientEntity
import app.diurna.data.recipe.ImportedRecipe
import app.diurna.data.recipe.RecipeImportResult
import app.diurna.data.recipe.toIngredientEntities
import app.diurna.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/** "160 kcal · P 30 · C 5 · F 3" */
fun MealEntity.macroLine(): String = "%,d kcal · P %d · C %d · F %d".format(kcal, proteinG, carbsG, fatG)

@Composable
fun CardioCard(cardio: Boolean, onCardio: (Boolean) -> Unit) {
    SectionCard("Cardio exercises") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Runs, rides, rows and machines like the elliptical, logged by distance, time and pace instead of weight × reps. Off keeps them out of suggestions and Exercises.", Modifier.weight(1f))
            Switch(checked = cardio, onCheckedChange = onCardio)
        }
    }
}

@Composable
fun DetailedNutritionCard(
    detailedNutrition: Boolean, onDetailedNutrition: (Boolean) -> Unit,
    dvBasis: DvBasis, hasProfile: Boolean, onDvBasis: (DvBasis) -> Unit,
    onBackfill: () -> Unit,
) {
    SectionCard("Detailed nutrition") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Fiber, sugars, starch, fats, vitamins and minerals on meals and Day food rows, and a day's % Daily Value. Off keeps the basic protein / carbs / fat view.", Modifier.weight(1f))
            Switch(checked = detailedNutrition, onCheckedChange = onDetailedNutrition)
        }
        if (detailedNutrition) {
            DvBasisChoice(dvBasis, hasProfile, onDvBasis)
            OutlinedButton(onClick = onBackfill) { Text("Fill in past entries") }
        }
    }
}

@Composable
private fun DvBasisChoice(selected: DvBasis, hasProfile: Boolean, onPick: (DvBasis) -> Unit) {
    Column(Modifier.selectableGroup()) {
        Text("Daily Values from", fontWeight = FontWeight.SemiBold)
        listOf(
            DvBasis.FDA to ("FDA label, 2,000 kcal" to "The numbers on US food packaging. The same for everyone."),
            DvBasis.SCALED to ("Scaled to my calorie target" to "Fiber, saturated fat and added sugars scale with each day's kcal target. Cholesterol, sodium, vitamins and minerals stay put."),
            DvBasis.AGE_SEX to ("By age and sex" to
                if (hasProfile) "US recommended amounts for the age and sex in Targets, for ages 19 and up; under 19 the FDA values are used. Iron, zinc and magnesium differ most."
                else "Needs the Targets profile and age 19 or older; until then the FDA values are used."),
        ).forEach { (basis, text) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = basis == selected, role = Role.RadioButton, onClick = { onPick(basis) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = basis == selected, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(text.first)
                    Hint(text.second)
                }
            }
        }
    }
}

@Composable
fun PageHeader(title: String?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onBack, contentPadding = PaddingValues(start = 10.dp, end = 16.dp)) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = null, Modifier.size(22.dp))
            Text(LocalBackLabel.current)
        }
        if (title != null) Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

/** Where a page's back button goes, by name; Setup's pages nest one or two deep. */
val LocalBackLabel = androidx.compose.runtime.staticCompositionLocalOf { "Setup" }

@Composable
fun MealsPage(vm: LibraryViewModel, onBack: () -> Unit) {
    val meals by vm.meals.collectAsStateWithLifecycle()
    val allMeals by vm.allMeals.collectAsStateWithLifecycle()
    val detailed by vm.detailedNutrition.collectAsStateWithLifecycle()
    val filter by vm.mealKindFilter.collectAsStateWithLifecycle()
    val hasFdcKey by vm.hasFdcKey.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<MealEntity?>(null) }
    var editingRecipe by remember { mutableStateOf<MealEntity?>(null) }
    var recipeLines by remember { mutableStateOf<List<RecipeIngredientEntity>>(emptyList()) }
    var importUrlOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<MealEntity?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeader("Meals", onBack) }
        item {
            Hint("Saved meals, recipes, and restaurant dishes with exact numbers. Pick one in the Day screen's food box to log it instantly.")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { vm.setMealFilter(null) }, label = { Text("All") })
                FilterChip(selected = filter == MealEntity.KIND_SIMPLE, onClick = { vm.setMealFilter(MealEntity.KIND_SIMPLE) }, label = { Text("Meals") })
                FilterChip(selected = filter == MealEntity.KIND_RECIPE, onClick = { vm.setMealFilter(MealEntity.KIND_RECIPE) }, label = { Text("Recipes") })
                FilterChip(selected = filter == MealEntity.KIND_RESTAURANT, onClick = { vm.setMealFilter(MealEntity.KIND_RESTAURANT) }, label = { Text("Restaurants") })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = MealEntity(name = "", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0, kind = MealEntity.KIND_SIMPLE) }) {
                    Text("Add meal")
                }
                Button(onClick = {
                    editingRecipe = MealEntity(name = "", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0, kind = MealEntity.KIND_RECIPE, servingsYield = 1.0)
                    recipeLines = emptyList()
                }) { Text("Add recipe") }
                Button(onClick = { importUrlOpen = true }) { Text("Import URL") }
                Button(onClick = {
                    editing = MealEntity(
                        name = "", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0,
                        kind = MealEntity.KIND_RESTAURANT,
                        nutritionSource = MealEntity.SOURCE_ESTIMATE,
                    )
                }) { Text("Add restaurant") }
            }
        }
        if (meals.isEmpty()) item { Hint("Nothing here yet. Add a meal, recipe, or restaurant dish, or save one from a Day food entry.") }
        items(meals, key = { it.id }) { m ->
            Row(
                Modifier.fillMaxWidth().clickable(onClickLabel = "Edit") {
                    if (m.isRecipe) {
                        scope.launch {
                            recipeLines = vm.loadIngredients(m.id)
                            editingRecipe = m
                            if (recipeLines.any { it.grams == null }) vm.enqueueDensityLookup(m.id)
                        }
                    } else {
                        editing = m
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, fontWeight = FontWeight.SemiBold)
                        if (m.isRecipe) {
                            Text("Recipe", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (m.isRestaurant) {
                            Text("Restaurant", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(m.macroLine(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    if (m.isRestaurant) {
                        val src = when (m.nutritionSource) {
                            MealEntity.SOURCE_PUBLISHED -> "Published nutrition"
                            else -> "Estimate"
                        }
                        Hint(listOfNotNull(m.restaurantName, src).joinToString(" · "))
                    }
                    m.note?.let { Hint(it) }
                    if (m.isRecipe && m.aminoPartial) {
                        Hint("Aminos partial (${m.aminoMatched}/${m.aminoTotal})")
                    }
                }
                TextButton(onClick = { confirmDelete = m }) { Text("Delete") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
    editing?.let { m ->
        val mealGallery by vm.mealPhotos(m.id).collectAsStateWithLifecycle(emptyList())
        MealDialog(
            when {
                m.isRestaurant && m.id == 0L -> "Add restaurant dish"
                m.isRestaurant -> "Edit restaurant dish"
                m.id == 0L -> "Add meal"
                else -> "Edit meal"
            },
            m,
            detailed = detailed,
            labelHost = vm,
            initialPhotos = if (m.id == 0L) emptyList() else mealGallery,
            onDismiss = { editing = null },
            onSave = vm::saveMeal,
            onSaveWithPhotos = vm::saveMealWithPhotos,
        )
    }
    editingRecipe?.let { m ->
        RecipeDialog(
            title = if (m.id == 0L) "Add recipe" else "Edit recipe",
            initial = m,
            initialLines = recipeLines,
            detailed = detailed,
            hasFdcKey = hasFdcKey,
            savedMeals = allMeals,
            searchFdc = vm::searchFdc,
            loadFdcNutrition = vm::loadFdcNutrition,
            scanBarcode = vm::scanBarcode,
            fillDensities = vm::resolveDensities,
            logEvent = vm::logRecipeEditor,
            onDismiss = { editingRecipe = null },
            onSave = vm::saveRecipe,
        )
    }
    if (importUrlOpen) {
        ImportRecipeUrlDialog(
            onDismiss = { importUrlOpen = false },
            onImport = { url -> vm.importRecipeFromUrl(url) },
            onImported = { imported ->
                importUrlOpen = false
                recipeLines = imported.toIngredientEntities()
                editingRecipe = imported.toMealDraft()
            },
        )
    }
    confirmDelete?.let { m ->
        ConfirmDialog(
            "Delete ${m.name}?", "Entries you've already logged keep their numbers.", "Delete",
            onDismiss = { confirmDelete = null },
        ) { vm.deleteMeal(m.id); confirmDelete = null }
    }
}

/**
 * Add/edit a saved meal. Also used by "Save as meal" on a food entry. Stays open to show problems.
 * [detailed] shows fiber / sugars / lipid fields (Setup → Detailed nutrition).
 * [labelHost] enables "Fill from label photo" (simple meals) or "Estimate from menu photo"
 * (restaurant meals). AI fills the fields; Save is the confirm step.
 */
@Composable
fun MealDialog(
    title: String,
    initial: MealEntity,
    detailed: Boolean = false,
    labelHost: PhotoImportHost? = null,
    initialPhotos: List<String> = emptyList(),
    onSaveWithPhotos: (suspend (MealEntity, List<String>) -> Result)? = null,
    onDismiss: () -> Unit,
    onSave: suspend (MealEntity) -> Result,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var kcal by remember { mutableStateOf(initial.kcal.takeIf { it > 0 }?.toString() ?: "") }
    // Blank rather than "0" for a new meal, so there's nothing to delete before typing.
    fun start(x: Int) = if (x == 0 && initial.kcal == 0) "" else x.toString()
    fun startOpt(x: Int?) = x?.toString() ?: ""
    var p by remember { mutableStateOf(start(initial.proteinG)) }
    var c by remember { mutableStateOf(start(initial.carbsG)) }
    var f by remember { mutableStateOf(start(initial.fatG)) }
    var note by remember { mutableStateOf(initial.note ?: "") }
    val isRestaurant = initial.isRestaurant
    var restaurantName by remember { mutableStateOf(initial.restaurantName ?: "") }
    var nutritionSource by remember {
        mutableStateOf(initial.nutritionSource ?: MealEntity.SOURCE_ESTIMATE)
    }
    var fiber by remember { mutableStateOf(startOpt(initial.fiberG)) }
    var solFiber by remember { mutableStateOf(startOpt(initial.solubleFiberG)) }
    var insolFiber by remember { mutableStateOf(startOpt(initial.insolubleFiberG)) }
    var sugars by remember { mutableStateOf(startOpt(initial.sugarsG)) }
    var addedSugars by remember { mutableStateOf(startOpt(initial.addedSugarsG)) }
    var starch by remember { mutableStateOf(startOpt(initial.starchG)) }
    var sat by remember { mutableStateOf(startOpt(initial.satFatG)) }
    var mono by remember { mutableStateOf(startOpt(initial.monoFatG)) }
    var poly by remember { mutableStateOf(startOpt(initial.polyFatG)) }
    var trans by remember { mutableStateOf(startOpt(initial.transFatG)) }
    var chol by remember { mutableStateOf(startOpt(initial.cholesterolMg)) }
    var o3 by remember { mutableStateOf(startOpt(initial.omega3G)) }
    var o6 by remember { mutableStateOf(startOpt(initial.omega6G)) }
    var o9 by remember { mutableStateOf(startOpt(initial.omega9G)) }
    var his by remember { mutableStateOf(startOpt(initial.hisMg)) }
    var ile by remember { mutableStateOf(startOpt(initial.ileMg)) }
    var leu by remember { mutableStateOf(startOpt(initial.leuMg)) }
    var lys by remember { mutableStateOf(startOpt(initial.lysMg)) }
    var met by remember { mutableStateOf(startOpt(initial.metMg)) }
    var phe by remember { mutableStateOf(startOpt(initial.pheMg)) }
    var thr by remember { mutableStateOf(startOpt(initial.thrMg)) }
    var trp by remember { mutableStateOf(startOpt(initial.trpMg)) }
    var valA by remember { mutableStateOf(startOpt(initial.valMg)) }
    var cys by remember { mutableStateOf(startOpt(initial.cysMg)) }
    var tyr by remember { mutableStateOf(startOpt(initial.tyrMg)) }
    var problem by remember { mutableStateOf<String?>(null) }
    var fromPhoto by remember { mutableStateOf(false) }
    var readingPhoto by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    var gallery by rememberSaveable { mutableStateOf(initialPhotos) }
    var galleryDrafts by rememberSaveable { mutableStateOf(listOf<String>()) }
    val libraryVm = labelHost as? LibraryViewModel
    val dayVm = labelHost as? DayViewModel
    val canLabel = !isRestaurant && (libraryVm != null || dayVm != null)
    val canEstimate = isRestaurant && libraryVm != null
    val attacher = if (labelHost != null && (canLabel || canEstimate)) {
        rememberPhotoAttacher(labelHost) { photoName ->
            readingPhoto = true
            scope.launch {
                if (canEstimate) {
                    when (val r = libraryVm!!.estimateRestaurantPhoto(photoName, name, restaurantName, detailed)) {
                        is RestaurantEstimateResult.Ok -> {
                            val e = r.estimate
                            kcal = ((e.kcalLow + e.kcalHigh) / 2).toString()
                            p = e.proteinG.toString()
                            c = e.carbsG.toString()
                            f = e.fatG.toString()
                            if (e.note.isNotBlank()) note = e.note
                            if (detailed) {
                                fiber = e.fiberG?.toString() ?: fiber
                                sugars = e.sugarsG?.toString() ?: sugars
                                addedSugars = e.addedSugarsG?.toString() ?: addedSugars
                                sat = e.satFatG?.toString() ?: sat
                                mono = e.monoFatG?.toString() ?: mono
                                poly = e.polyFatG?.toString() ?: poly
                                trans = e.transFatG?.toString() ?: trans
                                chol = e.cholesterolMg?.toString() ?: chol
                                o3 = e.omega3G?.toString() ?: o3
                                o6 = e.omega6G?.toString() ?: o6
                                o9 = e.omega9G?.toString() ?: o9
                            }
                            nutritionSource = MealEntity.SOURCE_ESTIMATE
                            fromPhoto = true
                            problem = null
                        }
                        is RestaurantEstimateResult.Problem -> {
                            fromPhoto = true
                            problem = r.message
                        }
                    }
                } else {
                    val read = when {
                        libraryVm != null -> libraryVm.readLabelPhoto(photoName)
                        dayVm != null -> dayVm.readLabelPhoto(photoName)
                        else -> LabelPhotoResult.Problem("No reader")
                    }
                    when (read) {
                        is LabelPhotoResult.Read -> {
                            val lab = read.reading
                            if (name.isBlank() && lab.productName.isNotBlank()) name = lab.productName
                            if (lab.serving.isNotBlank()) note = lab.serving
                            kcal = lab.kcal.toString()
                            p = lab.proteinG.toString()
                            c = lab.carbsG.toString()
                            f = lab.fatG.toString()
                            if (detailed) {
                                fiber = lab.fiberG?.toString() ?: ""
                                sugars = lab.sugarsG?.toString() ?: ""
                                addedSugars = lab.addedSugarsG?.toString() ?: ""
                                sat = lab.satFatG?.toString() ?: ""
                                trans = lab.transFatG?.toString() ?: ""
                                chol = lab.cholesterolMg?.toString() ?: ""
                            }
                            fromPhoto = true
                            problem = null
                        }
                        is LabelPhotoResult.Problem -> {
                            fromPhoto = true
                            problem = read.message
                        }
                    }
                }
                readingPhoto = false
            }
        }
    } else null
    val galleryAttacher = if (onSaveWithPhotos != null && labelHost != null) {
        rememberPhotoAttacher(labelHost) { newPhoto ->
            galleryDrafts = galleryDrafts + newPhoto
            gallery = (gallery + newPhoto).distinct().take(5)
        }
    } else null
    val valid = name.isNotBlank() && kcal.toIntOrNull() != null &&
        (!isRestaurant || restaurantName.isNotBlank())
    fun opt(s: String): Int? = s.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.formDialogWidth(),
        properties = FormDialogProperties,
        title = { Text(title) },
        text = {
            // Scrolls: with detailed nutrition on, the fields run past the screen.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                if (isRestaurant) {
                    OutlinedTextField(
                        restaurantName, { restaurantName = it }, Modifier.fillMaxWidth(),
                        label = { Text("Restaurant") }, placeholder = { Text("Chipotle") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = nutritionSource == MealEntity.SOURCE_PUBLISHED,
                            onClick = { nutritionSource = MealEntity.SOURCE_PUBLISHED },
                            label = { Text("Published") },
                        )
                        FilterChip(
                            selected = nutritionSource == MealEntity.SOURCE_ESTIMATE,
                            onClick = { nutritionSource = MealEntity.SOURCE_ESTIMATE },
                            label = { Text("Estimate") },
                        )
                    }
                    Hint("Published = chain nutrition page / labeled values. Estimate = AI or your guess.")
                }
                if (attacher != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            TextButton(
                                onClick = { photoMenu = true },
                                enabled = !readingPhoto && !attacher.busy,
                            ) {
                                Text(if (isRestaurant) "Estimate from menu photo" else "Fill from label photo")
                            }
                            DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                                DropdownMenuItem(text = { Text("Take photo") }, onClick = { photoMenu = false; attacher.takePhoto() })
                                DropdownMenuItem(text = { Text("Choose photo") }, onClick = { photoMenu = false; attacher.choosePhoto() })
                            }
                        }
                        if (readingPhoto || attacher.busy) {
                            Hint(if (isRestaurant) "Estimating from photo…" else "Reading the label…")
                        }
                    }
                    attacher.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn) }
                    if (fromPhoto && problem == null) {
                        Hint(if (isRestaurant) "Check the estimate, then Save." else "Check the numbers from the label, then Save.")
                    }
                }
                if (galleryAttacher != null && libraryVm != null) {
                    Hint("Photos of this meal (optional).")
                    PhotoGalleryEditor(
                        libraryVm.foodPhotos,
                        gallery,
                        galleryAttacher,
                        onChange = { next -> gallery = next },
                    )
                }
                OutlinedTextField(kcal, { kcal = it }, Modifier.fillMaxWidth(), label = { Text("Calories") }, suffix = { Text("kcal") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                NumberInputRows(listOf(
                    NumberInput("Protein", p, "g", placeholder = "0") { p = it },
                    NumberInput("Carbs", c, "g", placeholder = "0") { c = it },
                    NumberInput("Fat", f, "g", placeholder = "0") { f = it },
                ))
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Serving (optional)") }, placeholder = { Text("1 bottle, 11 oz") }, singleLine = true)
                if (detailed) {
                    Hint("Leave a field blank when unknown (distinct from 0). Carbs are total carbs including fiber.")
                    NumberInputRows(listOf(
                        NumberInput("Fiber", fiber, "g") { fiber = it },
                        NumberInput("Soluble", solFiber, "g") { solFiber = it },
                        NumberInput("Insol.", insolFiber, "g") { insolFiber = it },
                    ))
                    NumberInputRows(listOf(
                        NumberInput("Sugars", sugars, "g") { sugars = it },
                        NumberInput("Added", addedSugars, "g") { addedSugars = it },
                        NumberInput("Starch", starch, "g") { starch = it },
                    ))
                    NumberInputRows(listOf(
                        NumberInput("Sat", sat, "g") { sat = it },
                        NumberInput("Mono", mono, "g") { mono = it },
                        NumberInput("Poly", poly, "g") { poly = it },
                        NumberInput("Trans", trans, "g") { trans = it },
                        NumberInput("Chol", chol, "mg") { chol = it },
                    ))
                    NumberInputRows(listOf(
                        NumberInput("Ω3", o3, "g") { o3 = it },
                        NumberInput("Ω6", o6, "g") { o6 = it },
                        NumberInput("Ω9", o9, "g") { o9 = it },
                    ))
                    Hint("Amino acids (mg). Optional; blank = unknown.")
                    NumberInputRows(listOf(
                        NumberInput("His", his, "mg") { his = it },
                        NumberInput("Ile", ile, "mg") { ile = it },
                        NumberInput("Leu", leu, "mg") { leu = it },
                        NumberInput("Lys", lys, "mg") { lys = it },
                        NumberInput("Met", met, "mg") { met = it },
                        NumberInput("Phe", phe, "mg") { phe = it },
                        NumberInput("Thr", thr, "mg") { thr = it },
                        NumberInput("Trp", trp, "mg") { trp = it },
                        NumberInput("Val", valA, "mg") { valA = it },
                        NumberInput("Cys", cys, "mg") { cys = it },
                        NumberInput("Tyr", tyr, "mg") { tyr = it },
                    ))
                }
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val meal = initial.copy(
                    name = name, kcal = kcal.toIntOrNull() ?: 0, proteinG = p.toIntOrNull() ?: 0,
                    carbsG = c.toIntOrNull() ?: 0, fatG = f.toIntOrNull() ?: 0, note = note.ifBlank { null },
                    kind = if (isRestaurant) MealEntity.KIND_RESTAURANT else MealEntity.KIND_SIMPLE,
                    restaurantName = if (isRestaurant) restaurantName.ifBlank { null } else null,
                    nutritionSource = if (isRestaurant) nutritionSource else null,
                    fiberG = if (detailed) opt(fiber) else initial.fiberG,
                    solubleFiberG = if (detailed) opt(solFiber) else initial.solubleFiberG,
                    insolubleFiberG = if (detailed) opt(insolFiber) else initial.insolubleFiberG,
                    sugarsG = if (detailed) opt(sugars) else initial.sugarsG,
                    addedSugarsG = if (detailed) opt(addedSugars) else initial.addedSugarsG,
                    starchG = if (detailed) opt(starch) else initial.starchG,
                    satFatG = if (detailed) opt(sat) else initial.satFatG,
                    monoFatG = if (detailed) opt(mono) else initial.monoFatG,
                    polyFatG = if (detailed) opt(poly) else initial.polyFatG,
                    transFatG = if (detailed) opt(trans) else initial.transFatG,
                    cholesterolMg = if (detailed) opt(chol) else initial.cholesterolMg,
                    omega3G = if (detailed) opt(o3) else initial.omega3G,
                    omega6G = if (detailed) opt(o6) else initial.omega6G,
                    omega9G = if (detailed) opt(o9) else initial.omega9G,
                    hisMg = if (detailed) opt(his) else initial.hisMg,
                    ileMg = if (detailed) opt(ile) else initial.ileMg,
                    leuMg = if (detailed) opt(leu) else initial.leuMg,
                    lysMg = if (detailed) opt(lys) else initial.lysMg,
                    metMg = if (detailed) opt(met) else initial.metMg,
                    pheMg = if (detailed) opt(phe) else initial.pheMg,
                    thrMg = if (detailed) opt(thr) else initial.thrMg,
                    trpMg = if (detailed) opt(trp) else initial.trpMg,
                    valMg = if (detailed) opt(valA) else initial.valMg,
                    cysMg = if (detailed) opt(cys) else initial.cysMg,
                    tyrMg = if (detailed) opt(tyr) else initial.tyrMg,
                )
                scope.launch {
                    val r = if (onSaveWithPhotos != null) {
                        onSaveWithPhotos(meal, gallery)
                    } else {
                        onSave(meal)
                    }
                    if (libraryVm != null) {
                        galleryDrafts.filter { name -> name !in gallery }.forEach(libraryVm::discardPhoto)
                    }
                    when (r) {
                        Result.Ok -> onDismiss()
                        is Result.Problem -> problem = r.message
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ExercisesPage(vm: LibraryViewModel, onBack: () -> Unit) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val cardioOn by vm.cardio.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<CatalogExercise?>(null) }
    var newName by remember { mutableStateOf("") }
    var addProblem by remember { mutableStateOf<String?>(null) }
    // Null: strength. Only offered with Setup's Cardio switch on.
    var newCardio by remember { mutableStateOf<Cardio.Style?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { PageHeader("Exercises", onBack) }
        item {
            Hint(
                "Renaming keeps history: workouts point at the exercise, not its spelling. Exercises with history can't be deleted, only hidden from suggestions." +
                    if (cardioOn) " Cardio exercises log distance, time and pace instead of weight × reps." else ""
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    newName, { newName = it; addProblem = null }, Modifier.weight(1f), placeholder = { Text("New exercise") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                if (cardioOn) {
                    FilterChip(newCardio != null, { newCardio = if (newCardio == null) Cardio.Style.TIME else null }, label = { Text("Cardio") })
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        when (val r = vm.addExercise(newName, newCardio.takeIf { cardioOn })) {
                            Result.Ok -> { newName = ""; newCardio = null }
                            is Result.Problem -> addProblem = r.message
                        }
                    }
                }) { Text("Add") }
            }
            newCardio?.takeIf { cardioOn }?.let { st -> StylePicker(st) { newCardio = it } }
            addProblem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
        }
        items(catalog, key = { it.id }) { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, color = if (e.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    e.note?.let {
                        Text(
                            it.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Hint(
                        listOfNotNull(
                            e.style.label.lowercase().takeIf { e.isCardio },
                            "hidden".takeIf { e.hidden },
                            "logged ${e.logged}×".takeIf { e.logged > 0 },
                            "in a routine workout".takeIf { e.inRoutine > 0 },
                            "unused".takeIf { !e.inUse },
                        ).joinToString(" · ")
                    )
                }
                TextButton(onClick = { renaming = e }, contentPadding = PaddingValues(6.dp)) { Text("Edit") }
                if (e.inUse || e.hidden) {
                    TextButton(onClick = { vm.setExerciseHidden(e.id, !e.hidden) }, contentPadding = PaddingValues(6.dp)) { Text(if (e.hidden) "Show" else "Hide") }
                } else {
                    TextButton(onClick = { vm.deleteExercise(e.id) }, contentPadding = PaddingValues(6.dp)) { Text("Delete") }
                }
            }
        }
    }
    renaming?.let { e ->
        // With cardio off this is the plain rename it always was; cardio already logged still gets its style.
        val editKind = cardioOn || e.isCardio
        ExerciseDialog(e, editKind, onDismiss = { renaming = null }) { name, cardio, note ->
            val r = vm.renameExercise(e.id, name)
            if (r == Result.Ok && note != e.note.orEmpty()) vm.setExerciseNote(e.id, note)
            val current = e.style.takeIf { e.isCardio }
            if (r == Result.Ok && editKind && cardio != current) vm.setExerciseCardio(e.id, cardio) else r
        }
    }
}

/** How a cardio exercise asks for and shows its numbers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StylePicker(selected: Cardio.Style, onPick: (Cardio.Style) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Cardio.Style.entries.forEach { st -> FilterChip(st == selected, { onPick(st) }, label = { Text(st.label) }) }
    }
}

@Composable
private fun ExerciseDialog(e: CatalogExercise, editKind: Boolean, onDismiss: () -> Unit, onSave: suspend (String, Cardio.Style?, String) -> Result) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(e.name) }
    var note by remember { mutableStateOf(e.note.orEmpty()) }
    // Null: strength.
    var cardio by remember { mutableStateOf(e.style.takeIf { e.isCardio }) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; problem = null }, Modifier.fillMaxWidth(), singleLine = true)
                if (e.logged > 0) Hint("Past workouts will show the new name.")
                OutlinedTextField(
                    note, { note = it.take(ExerciseNote.MAX_CHARS) }, Modifier.fillMaxWidth(),
                    label = { Text("Note (shows in every workout)") }, minLines = 2, maxLines = ExerciseNote.MAX_LINES,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supportingText = { Text("${note.length} / ${ExerciseNote.MAX_CHARS}") },
                )
                if (editKind) {
                    // Kind only changes before anything is logged, so sets and pieces never mix in history.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            cardio != null, { cardio = if (cardio == null) Cardio.Style.TIME else null },
                            enabled = e.logged == 0, label = { Text("Cardio") },
                        )
                        Hint(if (e.logged == 0) "Distance, time and pace instead of weight × reps" else "Logged exercises keep their kind")
                    }
                    cardio?.let { st ->
                        StylePicker(st) { cardio = it }
                        Hint("Changes which numbers it asks for and how pace reads. Logged pieces keep their numbers.")
                    }
                }
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    when (val r = onSave(name, cardio, note)) {
                        Result.Ok -> onDismiss()
                        is Result.Problem -> problem = r.message
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/**
 * Paste a recipe page URL. Fetches over hardened HTTPS and opens the recipe editor with
 * parsed ingredients (nutrition left for the user to fill). No AI call; no API keys sent.
 */
@Composable
private fun ImportRecipeUrlDialog(
    onDismiss: () -> Unit,
    onImport: suspend (String) -> RecipeImportResult,
    onImported: (ImportedRecipe) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import recipe from URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("Paste a link to a recipe page that uses schema.org Recipe markup (most major recipe sites). Ingredients open in the editor for you to confirm — nothing is saved until you tap Save.")
                OutlinedTextField(
                    url,
                    { url = it; problem = null },
                    Modifier.fillMaxWidth(),
                    label = { Text("https://…") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    busy = true
                    problem = null
                    scope.launch {
                        when (val r = onImport(url.trim())) {
                            is RecipeImportResult.Ok -> onImported(r.recipe)
                            is RecipeImportResult.Problem -> {
                                problem = r.message
                                busy = false
                            }
                        }
                    }
                },
            ) { Text(if (busy) "Fetching…" else "Import") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
