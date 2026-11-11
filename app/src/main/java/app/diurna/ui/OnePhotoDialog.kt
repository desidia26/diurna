package app.diurna.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.diurna.container
import app.diurna.data.LibraryRepository.ScanResult
import app.diurna.data.OffSnapshot
import app.diurna.data.OnePhotoCapture
import app.diurna.data.OnePhotoCapture.Capture
import app.diurna.data.OnePhotoCapture.CodeState
import app.diurna.data.OnePhotoCapture.LabelRead
import app.diurna.debug.Action
import app.diurna.debug.Screen
import app.diurna.domain.PackageMerge
import app.diurna.domain.PhotoCapture
import app.diurna.domain.ProductCode
import app.diurna.scan.readCapturedBarcode
import app.diurna.ui.theme.LocalStatusColors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * One photo → a recipe ingredient. Takes a picture of the package, then (via [OnePhotoCapture])
 * decodes its barcode on the phone and looks it up while the selected AI reads the label and
 * ingredients. The photo is deleted as soon as its bytes are read. [scan] is the library's
 * barcode lookup; live scanning stays available as a fallback. [fallbackCode] is the line's own
 * barcode when re-reading a label later.
 */
@Composable
fun OnePhotoIngredientDialog(
    scan: suspend (String, Boolean) -> ScanResult,
    onDismiss: () -> Unit,
    onAdd: (IngredientDraft) -> Unit,
    fallbackCode: String? = null,
    initialName: String? = null,
    initialGrams: Double? = null,
) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val flow = remember { UUID.randomUUID().toString() }
    fun event(name: String, result: Action.Result = Action.Result.OK, details: Map<String, String> = emptyMap()) =
        c.actions.dispatch(Action.Event(Screen.LIBRARY, name, flow, result = result, details = details))
    val pipeline = remember(c) {
        OnePhotoCapture(
            lookup = scan,
            fdc = { code -> if (c.fdcClient.hasApiKey()) c.fdcClient.brandedByGtin(code) else null },
            reader = c.packageReader::read,
            online = { c.network.online.value },
        ) { name, result, details ->
            event(name, if (result == "failed") Action.Result.FAILED else Action.Result.OK, details + ("outcome" to result))
        }
    }
    var capture by remember { mutableStateOf<Capture?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var liveScan by remember { mutableStateOf(false) }
    // true: next photo only re-reads the label of the current capture.
    var labelOnly by remember { mutableStateOf(false) }

    fun process() {
        val raw = c.photoImporter.captureFile
        busy = if (labelOnly) "Reading the label…" else "Reading the photo…"
        problem = null
        scope.launch {
            var barcodeCopy: File? = null
            try {
                // Full-resolution copy for the on-device barcode decoder; the AI gets the downscaled photo.
                barcodeCopy = if (labelOnly) null else try {
                    val dir = File(context.cacheDir, "barcode-capture").apply { mkdirs() }
                    File.createTempFile("barcode-raw-", ".jpg", dir).also { raw.copyTo(it, overwrite = true) }
                } catch (_: Exception) { null }
                val name = c.photoImporter.importCapture()
                val bytes = name?.let { c.foodPhotos.read(it) }
                c.foodPhotos.delete(name)
                event("onephoto.photo_deleted", details = mapOf("read" to (bytes != null).toString()))
                if (bytes == null && barcodeCopy == null) { problem = PhotoAiReads.COULDNT_READ_PHOTO; return@launch }
                val prev = capture
                capture = if (labelOnly && prev != null && bytes != null) pipeline.withLabel(prev, bytes) else {
                    val copy = barcodeCopy
                    pipeline.run(bytes, decode = {
                        if (copy == null) app.diurna.domain.ScanOutcome.Unreadable else readCapturedBarcode(copy)
                    }, fallbackCode = fallbackCode ?: prev?.barcode)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event("onephoto.pipeline", Action.Result.FAILED, mapOf("exception" to e.javaClass.simpleName))
                problem = "Couldn't read that photo. Try again."
            } finally {
                barcodeCopy?.delete()
                raw.delete()
                busy = null
                labelOnly = false
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val taken = PhotoCapture.shouldImport(ok, c.photoImporter.captureFile.length())
        event("onephoto.photo", if (taken) Action.Result.OK else Action.Result.CANCELLED)
        if (taken) process() else labelOnly = false
    }
    fun launchCamera() {
        val file = PhotoCapture.prepareScratch(c.photoImporter.captureFile)
        try {
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.photos", file))
        } catch (_: ActivityNotFoundException) { problem = "No camera app found" }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else problem = "Camera permission is needed to take a photo"
    }
    fun takePhoto(onlyLabel: Boolean) {
        labelOnly = onlyLabel
        problem = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        event("onephoto.open", details = mapOf("relabel" to (fallbackCode != null).toString()))
        takePhoto(false)
    }

    val current = capture
    when {
        busy != null || current == null -> AlertDialog(
            onDismissRequest = { event("onephoto.cancel", Action.Result.CANCELLED); onDismiss() },
            title = { Text("Package photo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(busy ?: "Take one photo showing the barcode, the Nutrition Facts and the ingredients, as much as fits. Diurna reads the barcode on the phone; the AI reads the label. The photo isn't kept.")
                    problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                if (busy == null) TextButton(onClick = { takePhoto(false) }) { Text("Take photo") }
            },
            dismissButton = {
                Row {
                    if (busy == null) TextButton(onClick = { liveScan = true }) { Text("Live scan") }
                    TextButton(onClick = { event("onephoto.cancel", Action.Result.CANCELLED); onDismiss() }) { Text("Cancel") }
                }
            },
        )
        else -> OnePhotoConfirm(
            capture = current,
            initialName = initialName,
            initialGrams = initialGrams,
            problem = problem,
            onChooseCode = { code ->
                busy = "Looking up the barcode…"
                scope.launch { try { capture = pipeline.withCode(current, code) } finally { busy = null } }
            },
            onRetake = { capture = null; takePhoto(false) },
            onLiveScan = { liveScan = true },
            onReadLabel = { takePhoto(true) },
            onSave = { draft ->
                event("onephoto.save", details = mapOf(
                    "servings" to (draft.captured?.servingGrams != null).toString(),
                    "manual_fields" to (draft.captured?.facts()?.values?.count { it.source == PackageMerge.Source.MANUAL } ?: 0).toString(),
                    "label_pending" to (draft.captured?.labelPending() == true).toString(),
                ))
                onAdd(draft)
            },
            onCancel = { event("onephoto.cancel", Action.Result.CANCELLED); onDismiss() },
        )
    }

    if (liveScan) {
        BarcodeScanDialog(
            scan = scan,
            onKnown = { meal ->
                liveScan = false
                scope.launch { capture = pipeline.withScan(capture ?: Capture(CodeState.None, LabelRead.NoPhoto), ScanResult.Known(meal)) }
            },
            onFound = { draft ->
                liveScan = false
                // "Add manually" hands back a draft whose lookup failed; keep its barcode, not its zeros.
                val result = if (OffSnapshot.decode(draft.offSnapshotJson)?.lookupOk == true) ScanResult.Found(draft) else ScanResult.NotFound(draft)
                scope.launch { capture = pipeline.withScan(capture ?: Capture(CodeState.None, LabelRead.NoPhoto), result) }
            },
            onClose = { liveScan = false },
        )
    }
}

private fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

private fun fmt(v: Double): String = when {
    v >= 100 -> "%.0f".format(v)
    v >= 10 -> "%.1f".format(v).removeSuffix(".0")
    else -> "%.2f".format(v).trimEnd('0').removeSuffix(".")
}

/** The confirm screen. Pure of the camera and network so device tests can drive it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnePhotoConfirm(
    capture: Capture,
    onChooseCode: (ProductCode) -> Unit,
    onRetake: () -> Unit,
    onLiveScan: () -> Unit,
    onReadLabel: () -> Unit,
    onSave: (IngredientDraft) -> Unit,
    onCancel: () -> Unit,
    initialName: String? = null,
    initialGrams: Double? = null,
    problem: String? = null,
) {
    var name by remember(capture) { mutableStateOf(initialName?.ifBlank { null } ?: capture.suggestedName) }
    var amountStr by remember { mutableStateOf(initialGrams?.let(::fmt) ?: "") }
    var servings by remember { mutableStateOf(false) }
    val typed = remember { mutableStateMapOf<String, String>() }
    var servingGramsStr by remember { mutableStateOf("") }
    var typedPerServing by remember { mutableStateOf(false) }
    val fill = ManualFill(
        servingGrams = num(servingGramsStr),
        values = typed.mapNotNull { (k, v) -> num(v)?.let { k to it } }.toMap(),
        perServing = typedPerServing,
    )
    val merged = mergedWith(capture, fill)
    val canServe = merged.servingGrams != null
    val byServing = servings && canServe
    val base = mergedWith(capture, ManualFill())
    val missing = ONE_PHOTO_REQUIRED.filter { it !in base.per100g }
    val result = onePhotoLine(capture, fill, name, num(amountStr), byServing)
    val status = LocalStatusColors.current

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Package ingredient") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (val code = capture.code) {
                    is CodeState.Read -> Hint("Barcode ${code.code}" + when (code.off) {
                        is ScanResult.Known -> " · in your library"
                        is ScanResult.Found -> ""
                        is ScanResult.Incomplete -> " · OFF has no usable numbers"
                        is ScanResult.NotFound -> " · not in Open Food Facts"
                        is ScanResult.Failed -> " · OFF lookup failed"
                        null -> " · lookup failed"
                    })
                    is CodeState.Choose -> {
                        Text("Check the printed digits, then pick the barcode:", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            code.codes.forEach { p -> AssistChip(onClick = { onChooseCode(p) }, label = { Text(p.digits) }) }
                        }
                    }
                    CodeState.None -> Hint("No barcode found in the photo.")
                }
                when (val l = capture.label) {
                    is LabelRead.Read -> {}
                    is LabelRead.NoPanel -> Hint("No Nutrition Facts panel in the photo.")
                    is LabelRead.Skipped -> Text("${l.message} Saved values can be updated from a label photo later.", color = status.warn, style = MaterialTheme.typography.bodySmall)
                    is LabelRead.Failed -> Text(l.message, color = status.warn, style = MaterialTheme.typography.bodySmall)
                    LabelRead.NoPhoto -> {}
                }
                val sources = capture.inputs.map { it.source }.distinct()
                if (sources.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sources.forEach { s -> FilterChip(selected = true, onClick = {}, label = { Text(sourceLabel(s)) }) }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)

                val rows = factRows(capture, ManualFill())
                if (rows.isNotEmpty()) {
                    Text("Per 100 g", fontWeight = FontWeight.SemiBold)
                    rows.forEach { r ->
                        val others = if (r.others.isEmpty()) "" else "  (" + r.others.joinToString(", ") { "${sourceLabel(it.source)} ${fmt(it.value)}" } + ")"
                        Text("${factLabel(r.key)} ${fmt(r.per100g)} ${factUnit(r.key)} · ${sourceLabel(r.source)}$others",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = if (r.others.isEmpty()) MaterialTheme.colorScheme.onSurface else status.warn)
                    }
                }
                if (PackageMerge.Flag.KCAL_MACROS_MISMATCH in merged.flags) Text("Calories don't match protein, carbs and fat; check the label.", color = status.warn, style = MaterialTheme.typography.bodySmall)
                if (PackageMerge.Flag.SERVING_WEIGHT_FROM_DATABASE in merged.flags) Hint("Serving weight came from a database, not the label.")
                merged.servingText?.let { Hint("Serving: $it" + (merged.servingGrams?.let { g -> " = ${fmt(g.value)} g (${sourceLabel(g.source)})" } ?: "")) }
                capture.merged.ingredients?.let { (text, src) -> Hint("Ingredients (${sourceLabel(src)}): " + text.take(240) + if (text.length > 240) "…" else "") }

                if (base.servingGrams == null && (missing.isNotEmpty() || PackageMerge.Flag.LABEL_NEEDS_SERVING_WEIGHT in base.flags)) {
                    OutlinedTextField(servingGramsStr, { servingGramsStr = it }, Modifier.fillMaxWidth(), label = { Text("Grams per serving") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                if (missing.isNotEmpty()) {
                    Text("Fill in what wasn't found", fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !typedPerServing, onClick = { typedPerServing = false }, label = { Text("per 100 g") })
                        FilterChip(selected = typedPerServing, onClick = { typedPerServing = true }, label = { Text("per serving") })
                    }
                    missing.forEach { k ->
                        OutlinedTextField(typed[k] ?: "", { typed[k] = it }, Modifier.fillMaxWidth(),
                            label = { Text("${factLabel(k).replaceFirstChar { it.uppercase() }} (${factUnit(k)})") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !byServing, onClick = { servings = false }, label = { Text("g") })
                    FilterChip(selected = byServing, enabled = canServe, onClick = { servings = true }, label = { Text("servings") })
                }
                OutlinedTextField(amountStr, { amountStr = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (byServing) "Amount (servings)" else "Amount (g)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                (result as? OnePhotoLine.Ok)?.draft?.let { d ->
                    Text("%,d kcal · P %d · C %d · F %d".format(d.kcal, d.nutrition.proteinG, d.nutrition.carbsG, d.nutrition.fatG),
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
                (result as? OnePhotoLine.Problem)?.let { if (amountStr.isNotBlank()) Hint(it.message) }
                problem?.let { Text(it, color = status.warn, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRetake) { Text("Retake") }
                    if (capture.labelLater || capture.label is LabelRead.NoPhoto) TextButton(onClick = onReadLabel) { Text("Read label") }
                    TextButton(onClick = onLiveScan) { Text("Live scan") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = result is OnePhotoLine.Ok, onClick = { (result as? OnePhotoLine.Ok)?.let { onSave(it.draft) } }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
