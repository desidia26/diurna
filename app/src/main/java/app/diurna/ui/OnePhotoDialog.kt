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
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.snapshots.SnapshotStateMap
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
import app.diurna.data.DecisionLog
import app.diurna.data.OnePhotoCapture.LabelStatus
import app.diurna.data.OnePhotoCapture.PhotoCodes
import app.diurna.data.OnePhotoCapture.Row
import app.diurna.data.diffReadings
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
 * One photo → recipe ingredients, one per package in the picture. Takes a picture, then (via
 * [OnePhotoCapture]) decodes every barcode on the phone and looks them up while the selected AI
 * reads each package's label, ingredients and identity and asks about anything ambiguous. The
 * photo's bytes stay in memory only for this dialog (so a refinement can resend them); its file
 * is deleted as soon as it's read. [scan] is the library's barcode lookup; live scanning stays as
 * a fallback. [fallbackCode] is the line's own barcode when re-reading a label later.
 */
@Composable
fun OnePhotoIngredientDialog(
    scan: suspend (String, Boolean) -> ScanResult,
    onDismiss: () -> Unit,
    onAdd: (List<IngredientDraft>) -> Unit,
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
            fdcByCode = { code -> if (c.fdcClient.hasApiKey()) c.fdcClient.brandedByGtin(code) else null },
            reader = c.packageReader,
            online = { c.network.online.value },
            fdcSearch = { q -> if (c.fdcClient.hasApiKey()) c.fdcClient.brandedSearch(q, 3) else emptyList() },
            offSearch = { q -> app.diurna.data.OpenFoodFacts().search(q) },
        ) { name, result, details ->
            event(name, if (result == "failed") Action.Result.FAILED else Action.Result.OK, details + ("outcome" to result))
        }
    }
    var capture by remember { mutableStateOf<Capture?>(null) }
    // The photo's bytes for a refinement in this session only; never written anywhere.
    var photoInMemory by remember { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var liveScan by remember { mutableStateOf(false) }
    var labelOnly by remember { mutableStateOf(false) }

    /** Opt-in debug: the decision log goes with a failure report like the scanner's photos do. */
    fun attachLog(cap: Capture?, why: String) {
        if (cap == null || !c.debug.enabled.value) return
        c.debug.registerAttachment(cap.log.toJson().toByteArray(), app.diurna.debug.AttachmentKind.DECISION_LOG, correlationId = flow)
        event("onephoto.decision_log", details = mapOf("why" to why, "entries" to cap.log.entries.size.toString()))
    }

    fun process() {
        val raw = c.photoImporter.captureFile
        busy = if (labelOnly) "Reading the label…" else "Reading the photo…"
        problem = null
        scope.launch {
            var barcodeCopy: File? = null
            var cropCopy: File? = null
            try {
                // Full-resolution copy for the on-device barcode decoder; the AI gets the downscaled photo.
                barcodeCopy = if (labelOnly) null else try {
                    val dir = File(context.cacheDir, "barcode-capture").apply { mkdirs() }
                    File.createTempFile("barcode-raw-", ".jpg", dir).also { raw.copyTo(it, overwrite = true) }
                } catch (_: Exception) { null }
                // Its own copy for finding and cutting out each package (the barcode decoder deletes its copy as it goes).
                cropCopy = if (labelOnly) null else try {
                    val dir = File(context.cacheDir, "barcode-capture").apply { mkdirs() }
                    File.createTempFile("crop-raw-", ".jpg", dir).also { raw.copyTo(it, overwrite = true) }
                } catch (_: Exception) { null }
                val name = c.photoImporter.importCapture()
                val bytes = name?.let { c.foodPhotos.read(it) }
                c.foodPhotos.delete(name)
                event("onephoto.photo_deleted", details = mapOf("read" to (bytes != null).toString()))
                if (bytes == null && barcodeCopy == null) { problem = PhotoAiReads.COULDNT_READ_PHOTO; return@launch }
                photoInMemory = bytes
                val prev = capture
                capture = if (labelOnly && prev != null && bytes != null) pipeline.withLabel(prev, bytes) else {
                    val copy = barcodeCopy
                    pipeline.run(bytes, decode = {
                        val positions = mutableMapOf<String, Double>()
                        val outcome = if (copy == null) app.diurna.domain.ScanOutcome.Unreadable else readCapturedBarcode(copy, positions = positions)
                        PhotoCodes(outcome, positions)
                    }, fallbackCode = fallbackCode ?: prev?.rows?.firstNotNullOfOrNull { it.code?.code },
                        // Several packages: each is found on the phone and read from its own close-up.
                        crops = cropCopy?.let { app.diurna.scan.PhotoPackageSource(it) })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event("onephoto.pipeline", Action.Result.FAILED, mapOf("exception" to e.javaClass.simpleName))
                problem = "Couldn't read that photo. Try again."
                attachLog(capture, "pipeline_failed")
            } finally {
                barcodeCopy?.delete()
                cropCopy?.delete()
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
    fun cancel() {
        event("onephoto.cancel", Action.Result.CANCELLED)
        attachLog(capture, "cancelled")
        photoInMemory = null
        onDismiss()
    }
    fun work(label: String, block: suspend () -> Capture) {
        busy = label
        scope.launch { try { capture = block() } finally { busy = null } }
    }

    val current = capture
    if (current == null) {
        AlertDialog(
            onDismissRequest = ::cancel,
            title = { Text("Package photo") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(busy ?: "Take one photo of the package, or of several packages side by side: barcodes, Nutrition Facts, ingredients and fronts, as much as fits. Diurna reads barcodes on the phone; the AI reads the labels. The photo isn't kept.")
                    problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { if (busy == null) TextButton(onClick = { takePhoto(false) }) { Text("Take photo") } },
            dismissButton = {
                Row {
                    if (busy == null) TextButton(onClick = { liveScan = true }) { Text("Live scan") }
                    TextButton(onClick = ::cancel) { Text("Cancel") }
                }
            },
        )
    } else {
        OnePhotoConfirm(
            capture = current,
            initialName = initialName,
            initialGrams = initialGrams,
            busy = busy,
            problem = problem ?: current.problem,
            onChooseCode = { code -> work("Looking up the barcode…") { pipeline.withCode(current, code) } },
            onRetake = { capture = null; photoInMemory = null; takePhoto(false) },
            onLiveScan = { liveScan = true },
            onReadLabel = { takePhoto(true) },
            onRefine = { answer ->
                event("onephoto.refine_start", details = mapOf("with_photo" to (photoInMemory != null).toString()))
                work("Refining…") { pipeline.refine(current, photoInMemory, answer) }
            },
            onRevert = { idx -> work("Looking up…") { pipeline.identifyAgain(pipeline.revert(current, idx)) } },
            onPick = { key, ref -> capture = pipeline.pick(current, key, ref) },
            onNote = { kind, text, product -> capture = current.note(kind, text, product) },
            onSave = { drafts ->
                event("onephoto.save", details = mapOf(
                    "lines" to drafts.size.toString(),
                    "manual_fields" to drafts.sumOf { d -> d.captured?.facts()?.values?.count { it.source == PackageMerge.Source.MANUAL } ?: 0 }.toString(),
                    "label_pending" to drafts.count { it.captured?.labelPending() == true }.toString(),
                    "guesses" to drafts.count { d -> DecisionLog.fromJson(d.captured?.decisionLogJson)?.entries?.any { it.text.startsWith("Values from a confirmed name guess") } == true }.toString(),
                ))
                photoInMemory = null
                onAdd(drafts)
            },
            onCancel = ::cancel,
        )
    }

    if (liveScan) {
        BarcodeScanDialog(
            scan = scan,
            onKnown = { meal ->
                liveScan = false
                work("Looking up…") { pipeline.withScan(capture ?: Capture(LabelStatus.NoPhoto), ScanResult.Known(meal)) }
            },
            onFound = { draft ->
                liveScan = false
                // "Add manually" hands back a draft whose lookup failed; keep its barcode, not its zeros.
                val result = if (OffSnapshot.decode(draft.offSnapshotJson)?.lookupOk == true) ScanResult.Found(draft) else ScanResult.NotFound(draft)
                work("Looking up…") { pipeline.withScan(capture ?: Capture(LabelStatus.NoPhoto), result) }
            },
            onClose = { liveScan = false },
        )
    }
}

private fun num(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

/** What the person typed or chose for one row of the confirm screen. */
data class RowState(
    val name: String,
    val amount: String = "",
    val servings: Boolean = false,
    val typed: Map<String, String> = emptyMap(),
    val servingGrams: String = "",
    val typedPerServing: Boolean = false,
    val removed: Boolean = false,
    val editing: Boolean = false,
) {
    val fill: ManualFill get() = ManualFill(
        servingGrams = num(servingGrams),
        values = typed.mapNotNull { (k, v) -> num(v)?.let { k to it } }.toMap(),
        perServing = typedPerServing,
    )
}

/** The confirm screen: one row per package, then one Save for all. Pure of camera and network for device tests. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnePhotoConfirm(
    capture: Capture,
    onChooseCode: (ProductCode) -> Unit,
    onRetake: () -> Unit,
    onLiveScan: () -> Unit,
    onReadLabel: () -> Unit,
    onRefine: (String) -> Unit,
    onRevert: (Int) -> Unit,
    onPick: (String, String?) -> Unit,
    onNote: (String, String, Int?) -> Unit,
    onSave: (List<IngredientDraft>) -> Unit,
    onCancel: () -> Unit,
    initialName: String? = null,
    initialGrams: Double? = null,
    busy: String? = null,
    problem: String? = null,
) {
    val rows = capture.rows
    val states: SnapshotStateMap<String, RowState> = remember { mutableStateMapOf() }
    rows.forEach { r ->
        if (r.key !in states) states[r.key] = RowState(
            name = (if (rows.size == 1) initialName?.ifBlank { null } else null) ?: r.suggestedName,
            amount = if (rows.size == 1) initialGrams?.let(::fmtNum).orEmpty() else "",
        )
    }
    var refineText by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    val status = LocalStatusColors.current
    val kept = rows.filter { states[it.key]?.removed != true }
    val results = kept.associate { r ->
        val st = states.getValue(r.key)
        val byServing = st.servings && servingsAvailable(r, st.fill)
        r.key to onePhotoLine(capture, r, st.fill, st.name, num(st.amount), byServing)
    }
    val drafts = results.values.mapNotNull { (it as? OnePhotoLine.Ok)?.draft }
    val canSave = busy == null && kept.isNotEmpty() && drafts.size == kept.size

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (rows.size > 1) "${rows.size} packages" else "Package ingredient") },
        text = {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                busy?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                if (capture.choose.isNotEmpty()) {
                    Text("Check the printed digits, then pick the barcode:", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        capture.choose.forEach { p -> AssistChip(onClick = { onChooseCode(p) }, label = { Text(p.digits) }) }
                    }
                }
                when (val l = capture.label) {
                    is LabelStatus.Skipped -> Text("${l.message} Saved values can be updated from a label photo later.", color = status.warn, style = MaterialTheme.typography.bodySmall)
                    is LabelStatus.Failed -> Text(l.message, color = status.warn, style = MaterialTheme.typography.bodySmall)
                    else -> {}
                }

                // AI questions and refinement: a new interpretation, never an overwrite.
                val reading = capture.reading
                if (reading != null) {
                    capture.questions.forEach { q ->
                        Text("AI asks: ${q.text}", fontWeight = FontWeight.Medium, color = status.warn)
                        if (q.suggestions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            q.suggestions.forEach { a ->
                                AssistChip(enabled = busy == null && capture.log.canRefine, onClick = { onRefine("Q: ${q.text}\nA: $a") }, label = { Text(a) })
                            }
                        }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(refineText, { refineText = it }, Modifier.weight(1f).testTag("refine"),
                            label = { Text(if (capture.questions.isEmpty()) "Correct the reading" else "Answer or correct") }, singleLine = false, maxLines = 3)
                        TextButton(enabled = busy == null && refineText.isNotBlank() && capture.log.canRefine,
                            onClick = { onRefine(refineText.trim()); refineText = "" }) { Text("Refine") }
                    }
                    val revs = capture.log.revisions
                    if (revs.size > 1) {
                        Text("Interpretation ${capture.log.current + 1} of ${revs.size}", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            revs.forEachIndexed { i, r ->
                                FilterChip(selected = i == capture.log.current, enabled = busy == null, onClick = { onRevert(i) },
                                    label = { Text(if (i == 0) "Original" else "${i + 1}: ${r.answer.orEmpty().substringAfter("A: ").take(18)}") })
                            }
                        }
                        val cur = revs[capture.log.current]
                        val base = cur.basedOn?.let { revs.getOrNull(it) } ?: revs.getOrNull(capture.log.current - 1)?.takeIf { capture.log.current > 0 }
                        if (base != null) diffReadings(base.reading, cur.reading).take(12).forEach {
                            Text("Δ $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                rows.forEach { r ->
                    val st = states.getValue(r.key)
                    HorizontalDivider()
                    if (st.removed) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Removed: ${st.name.ifBlank { r.suggestedName.ifBlank { "package" } }}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { states[r.key] = st.copy(removed = false); onNote(DecisionLog.EDIT, "Restored “${st.name}”", r.product) }) { Text("Undo") }
                        }
                        return@forEach
                    }
                    PackageRow(capture, r, st, results[r.key], update = { states[r.key] = it }, onPick = onPick, onRemove = {
                        states[r.key] = st.copy(removed = true); onNote(DecisionLog.EDIT, "Removed “${st.name}” from this save", r.product)
                    })
                }
                problem?.let { Text(it, color = status.warn, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRetake) { Text("Retake") }
                    if (capture.labelLater || capture.label is LabelStatus.NoPhoto) TextButton(onClick = onReadLabel) { Text("Read label") }
                    TextButton(onClick = onLiveScan) { Text("Live scan") }
                    TextButton(onClick = { showLog = true }) { Text("Decisions (${capture.log.entries.size})") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSave, onClick = { onSave(drafts) }) { Text(if (kept.size > 1) "Save ${kept.size}" else "Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
    if (showLog) DecisionLogDialog(capture.log, onDismiss = { showLog = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackageRow(
    capture: Capture,
    r: Row,
    st: RowState,
    result: OnePhotoLine?,
    update: (RowState) -> Unit,
    onPick: (String, String?) -> Unit,
    onRemove: () -> Unit,
) {
    val status = LocalStatusColors.current
    val merged = mergedWith(r, st.fill)
    val canServe = merged.servingGrams != null
    val byServing = st.servings && canServe
    val base = mergedWith(r, ManualFill())
    val missing = ONE_PHOTO_REQUIRED.filter { it !in base.per100g }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(st.name, { update(st.copy(name = it)) }, Modifier.fillMaxWidth().testTag("name-${r.key}"), label = { Text("Name") }, singleLine = true)
        r.code?.let { Hint("Barcode ${it.code}" + (if (r.reading != null && r.matchedBy.isNotEmpty()) " · matched by ${r.matchedBy}" else "")) }
        if (r.reading == null && r.code == null && capture.codes.isEmpty() && capture.reading == null) Hint("Nothing was found in the photo; enter it below.")
        if (r.reading != null && r.reading.sizeText.isNotEmpty()) Hint("Size: ${r.reading.sizeText}")
        val chips = sourceChips(r)
        if (chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { label -> FilterChip(selected = true, onClick = {}, label = { Text(label) }) }
        }
        if (r.candidates.isNotEmpty()) {
            Text("Guess from the package front: is it one of these?", style = MaterialTheme.typography.bodySmall, color = status.warn)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                r.candidates.forEach { cand ->
                    FilterChip(selected = r.picked?.ref == cand.ref, onClick = { onPick(r.key, if (r.picked?.ref == cand.ref) null else cand.ref) },
                        label = { Text("Guess: ${cand.title} (${sourceLabel(cand.source)})") })
                }
            }
        } else if (r.needsIdentity) Hint("No database match for this name; enter its values below.")

        val rows = factRows(r, ManualFill())
        val shown = if (st.editing) rows else rows.filter { it.key in ONE_PHOTO_REQUIRED || it.others.isNotEmpty() }
        if (shown.isNotEmpty()) {
            Text("Per 100 g", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            shown.forEach { f ->
                val others = if (f.others.isEmpty()) "" else "  (" + f.others.joinToString(", ") { "${sourceLabel(it.source)} ${fmtNum(it.value)}" } + ")"
                Text("${factLabel(f.key)} ${fmtNum(f.per100g)} ${factUnit(f.key)} · ${if (r.picked?.input?.source == f.source && r.code == null) "Guess · " else ""}${sourceLabel(f.source)}$others",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = if (f.others.isEmpty()) MaterialTheme.colorScheme.onSurface else status.warn)
            }
        }
        if (PackageMerge.Flag.KCAL_MACROS_MISMATCH in merged.flags) Text("Calories don't match protein, carbs and fat; check the label.", color = status.warn, style = MaterialTheme.typography.bodySmall)
        if (PackageMerge.Flag.SERVING_WEIGHT_FROM_DATABASE in merged.flags) Hint("Serving weight came from a database, not the label.")
        merged.servingText?.let { Hint("Serving: $it" + (merged.servingGrams?.let { g -> " = ${fmtNum(g.value)} g (${sourceLabel(g.source)})" } ?: "")) }
        if (st.editing) merged.ingredients?.let { (text, src) -> Hint("Ingredients (${sourceLabel(src)}): " + text.take(400) + if (text.length > 400) "…" else "") }

        if (base.servingGrams == null && (missing.isNotEmpty() || PackageMerge.Flag.LABEL_NEEDS_SERVING_WEIGHT in base.flags)) {
            OutlinedTextField(st.servingGrams, { update(st.copy(servingGrams = it)) }, Modifier.fillMaxWidth().testTag("serving-${r.key}"),
                label = { Text("Grams per serving") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        if (missing.isNotEmpty()) {
            Text("Fill in what wasn't found", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !st.typedPerServing, onClick = { update(st.copy(typedPerServing = false)) }, label = { Text("per 100 g") })
                FilterChip(selected = st.typedPerServing, onClick = { update(st.copy(typedPerServing = true)) }, label = { Text("per serving") })
            }
            missing.forEach { k ->
                OutlinedTextField(st.typed[k] ?: "", { update(st.copy(typed = st.typed + (k to it))) }, Modifier.fillMaxWidth().testTag("fill-${r.key}-$k"),
                    label = { Text("${factLabel(k).replaceFirstChar { it.uppercase() }} (${factUnit(k)})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            FilterChip(selected = !byServing, onClick = { update(st.copy(servings = false)) }, label = { Text("g") }, modifier = Modifier.testTag("grams-${r.key}"))
            FilterChip(selected = byServing, enabled = canServe, onClick = { update(st.copy(servings = true)) }, label = { Text("servings") }, modifier = Modifier.testTag("servings-${r.key}"))
            OutlinedTextField(st.amount, { update(st.copy(amount = it)) }, Modifier.weight(1f).testTag("amount-${r.key}"),
                label = { Text(if (byServing) "Servings" else "Grams") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        (result as? OnePhotoLine.Ok)?.draft?.let { d ->
            Text("%,d kcal · P %d · C %d · F %d".format(d.kcal, d.nutrition.proteinG, d.nutrition.carbsG, d.nutrition.fatG),
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        }
        (result as? OnePhotoLine.Problem)?.let { if (st.amount.isNotBlank() || missing.isEmpty()) Hint(it.message) }
        Row {
            TextButton(onClick = { update(st.copy(editing = !st.editing)) }, modifier = Modifier.testTag("edit-${r.key}")) { Text(if (st.editing) "Less" else "Edit") }
            TextButton(onClick = onRemove, modifier = Modifier.testTag("remove-${r.key}")) { Text("Remove") }
        }
    }
}

/** The decision log, oldest first; also opened from a saved ingredient. */
@Composable
fun DecisionLogDialog(log: DecisionLog, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Decisions") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (log.revisions.isNotEmpty()) Hint("${log.revisions.size} interpretation(s); in use: ${log.current + 1}. Earlier ones are kept unchanged.")
                log.entries.forEach { e ->
                    Text("${e.seq}. [${e.kind}] ${e.text}", style = MaterialTheme.typography.bodySmall)
                }
                if (log.entries.isEmpty()) Hint("No decisions recorded.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
