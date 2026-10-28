package app.diurna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.diurna.data.ExerciseSetEntity
import app.diurna.data.piece
import app.diurna.domain.Cardio
import app.diurna.domain.CardioPiece
import app.diurna.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** "3,260 m · 15:07 · 2:10/500m" (in [style]) for the filled pieces, or "" when none are. */
fun cardioSummary(sets: List<ExerciseSetEntity>, style: Cardio.Style): String =
    sets.filter { it.isDone }.joinToString(" · ") { pieceText(it.piece(), style) }

/**
 * One piece in its exercise's style: "3,260 m · 15:07 · 2:10/500m", "3.1 mi · 26:00 · 8:23/mi",
 * "30 min · 250 kcal"; [full] adds power, calories and heart rate whenever they were logged.
 */
fun pieceText(p: CardioPiece, style: Cardio.Style, full: Boolean = false): String {
    val split = Cardio.effectiveSplit(p.splitS, p.durationS, p.distanceM)
    val base = Cardio.summary(style, p.distanceM, p.durationS, split, p.watts.takeIf { full }, p.kcal)
    return listOfNotNull(
        base,
        p.watts?.takeIf { full && !style.watts }?.let { "$it W" },
        p.kcal?.takeIf { full && style.hasDistance && p.distanceM != null }?.let { "$it kcal" },
        p.avgHr?.takeIf { full }?.let { "$it bpm" },
    ).filter { it.isNotEmpty() }.joinToString(" · ")
}

/**
 * A cardio exercise on the Day screen: its pieces (usually one) as summaries, each entered by hand
 * or read from a photo of the machine's summary screen, always through [CardioDialog].
 */
@Composable
fun CardioPieces(ex: ExerciseUi, vm: DayViewModel, collapsed: Boolean) {
    val logged = ex.logged
    val sets = logged.sets
    val last = ex.last
    val style = logged.exercise.style
    if (collapsed) {
        Hint(cardioSummary(sets, style).ifEmpty { "Not logged yet" })
        return
    }
    Hint(
        if (last != null) "Last time, ${last.date.short()}: " + cardioSummary(last.sets, style).ifEmpty { "nothing logged" }
        else "First time logging this"
    )
    sets.forEachIndexed { i, set ->
        PieceRow(i + 1, set, style, showNumber = sets.size > 1, vm = vm, onDelete = { vm.deleteSet(set.id, logged.logged.id) })
    }
    Row {
        TextButton(onClick = { vm.addSet(logged.logged.id) }) { Text("+ Piece") }
        if (last != null) TextButton(onClick = { vm.useLast(logged.logged.id, logged.exercise.id) }) { Text("Same as last") }
    }
}

@Composable
private fun PieceRow(n: Int, set: ExerciseSetEntity, style: Cardio.Style, showNumber: Boolean, vm: DayViewModel, onDelete: () -> Unit) {
    val provider by vm.aiProvider.collectAsStateWithLifecycle()
    val providerLabel = provider.label
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CardioEdit?>(null) }
    var reading by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    val attacher = rememberPhotoAttacher(vm) { name ->
        reading = true
        scope.launch {
            val r = vm.readCardioPhoto(name)
            reading = false
            editing = when (r) {
                is CardioPhotoResult.Read -> CardioEdit(r.reading.piece, fromPhoto = true, note = r.reading.note)
                // Fall back to typing: same dialog, the problem shown at the top.
                is CardioPhotoResult.Problem -> CardioEdit(set.piece(), fromPhoto = true, problem = r.message)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showNumber) Text("$n", Modifier.width(18.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            when {
                reading || attacher.busy -> Hint("Reading the screen…")
                set.isDone -> Text(pieceText(set.piece(), style, full = true), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                else -> Hint("Not logged yet")
            }
            attacher.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn) }
            Row {
                TextButton(onClick = { editing = CardioEdit(set.piece()) }, enabled = !reading) { Text(if (set.isDone) "Edit" else "Enter") }
                Box {
                    TextButton(onClick = { photoMenu = true }, enabled = !reading && !attacher.busy) { Text("Log from photo") }
                    DropdownMenu(expanded = photoMenu, onDismissRequest = { photoMenu = false }) {
                        DropdownMenuItem(text = { Text("Take photo") }, onClick = { photoMenu = false; attacher.takePhoto() })
                        DropdownMenuItem(text = { Text("Choose photo") }, onClick = { photoMenu = false; attacher.choosePhoto() })
                    }
                }
            }
            // Matches the food-estimate disclosure in Setup: name the provider and what leaves the phone.
            Hint("The photo is sent once to $providerLabel to read the numbers; nothing else.")
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Text("×", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    editing?.let { e ->
        CardioDialog(e, style, onDismiss = { editing = null }) { vm.updatePiece(set.id, it); editing = null }
    }
}

/** What [CardioDialog] opens with: the piece so far, or a photo's reading to check. */
data class CardioEdit(val piece: CardioPiece, val fromPhoto: Boolean = false, val note: String = "", val problem: String? = null)

/** Text for the dialog's fields, and back, in the exercise's [Cardio.Style]. Pure, so it's unit tested. */
object CardioForm {
    data class Fields(
        /** In the style's unit: metres, km or miles. */
        val distance: String = "",
        val time: String = "",
        /** The style's pace ("2:10" per 500 m, "8:23" per mile) or speed ("16.2" mph). */
        val pace: String = "",
        val bestSplit: String = "",
        val watts: String = "",
        val kcal: String = "",
        val hr: String = "",
    )

    /** What the dialog asks for in [style]; any other field is still shown when it holds a value. */
    data class Labels(val distance: String?, val pace: String?, val bestSplit: Boolean, val watts: Boolean)

    fun labels(style: Cardio.Style) = Labels(
        distance = style.unitLabel.takeIf { style.hasDistance },
        pace = when {
            !style.hasDistance -> null
            style == Cardio.Style.ROW -> "Avg split"
            style.pace.isSpeed -> "Avg speed"
            else -> "Avg pace"
        },
        bestSplit = style == Cardio.Style.ROW,
        watts = style.watts,
    )

    fun fields(p: CardioPiece, style: Cardio.Style) = Fields(
        distance = p.distanceM?.let { distanceField(it, style) }.orEmpty(),
        time = p.durationS?.let(::clockField).orEmpty(),
        pace = p.splitS?.let { paceField(it, style) }.orEmpty(),
        bestSplit = p.bestSplitS?.let(::clockField).orEmpty(),
        watts = p.watts?.toString().orEmpty(),
        kcal = p.kcal?.toString().orEmpty(),
        hr = p.avgHr?.toString().orEmpty(),
    )

    private fun distanceField(m: Int, style: Cardio.Style): String {
        val unit = style.unitM ?: return ""
        if (unit == 1.0) return m.toString()
        return String.format(Locale.US, "%.2f", m / unit).trimEnd('0').trimEnd('.')
    }

    private fun paceField(splitS: Double, style: Cardio.Style): String {
        val v = style.pace.fromSplit(splitS) ?: return ""
        return if (style.pace.isSpeed) String.format(Locale.US, "%.1f", v) else clockField(v)
    }

    /** Keeps tenths a machine showed ("1:44.5"); whole seconds otherwise. */
    private fun clockField(s: Double): String {
        val tenths = (s * 10).roundToInt()
        return if (tenths % 10 == 0) Cardio.clock(s)
        else Cardio.clock((tenths / 10).toDouble()) + "." + (tenths % 10)
    }

    sealed interface Parsed
    data class Ok(val piece: CardioPiece) : Parsed
    data class Problem(val message: String) : Parsed

    /**
     * [keep] supplies what [style] doesn't ask for, so a time-only exercise's piece that was once
     * logged with a distance doesn't lose it when edited.
     */
    fun parse(f: Fields, style: Cardio.Style, keep: CardioPiece = CardioPiece()): Parsed {
        val l = labels(style)
        fun clock(text: String, label: String): Result<Double?> =
            if (text.isBlank()) Result.success(null)
            else Cardio.parseClock(text)?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException("$label should look like 15:07"))
        fun int(text: String, label: String): Result<Int?> {
            val t = text.trim().replace(",", "")
            if (t.isEmpty()) return Result.success(null)
            return t.toIntOrNull()?.takeIf { it >= 0 }?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException("$label should be a whole number"))
        }
        fun decimal(text: String, label: String): Result<Double?> {
            val t = text.trim().replace(",", "")
            if (t.isEmpty()) return Result.success(null)
            return t.toDoubleOrNull()?.takeIf { it >= 0 }?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException("$label should be a number"))
        }
        val unit = style.unitM
        val distance: Result<Int?> = when {
            unit == null -> Result.success(keep.distanceM)
            unit == 1.0 -> int(f.distance, "Distance")
            else -> decimal(f.distance, "Distance").map { d -> d?.let { (it * unit).roundToInt() } }
        }
        val time = clock(f.time, "Time")
        val pace: Result<Double?> = when {
            l.pace == null -> Result.success(keep.splitS)
            style.pace.isSpeed -> decimal(f.pace, l.pace).map { v -> v?.let { style.pace.toSplit(it) } }
            else -> clock(f.pace, l.pace).map { v -> v?.let { style.pace.toSplit(it) } }
        }
        val best = clock(f.bestSplit, "Best split")
        val watts = int(f.watts, "Avg power")
        val kcal = int(f.kcal, "Calories")
        val hr = int(f.hr, "Heart rate")
        listOf(distance, time, pace, best, watts, kcal, hr).firstNotNullOfOrNull { it.exceptionOrNull() }?.let { return Problem(it.message!!) }
        val p = CardioPiece(
            distance.getOrNull(), time.getOrNull(), pace.getOrNull(), best.getOrNull(),
            watts.getOrNull(), kcal.getOrNull(), hr.getOrNull(),
        ).clean()
        if (p.distanceM == null && p.durationS == null) return Problem(if (style.hasDistance) "Enter a distance or a time" else "Enter a time")
        return Ok(p)
    }
}

/**
 * One piece: the fields [style] asks for (distance, time, pace or speed, power, calories, heart
 * rate), plus any other the piece already has, e.g. read off a photo. Also the photo's confirm step.
 */
@Composable
fun CardioDialog(edit: CardioEdit, style: Cardio.Style, onDismiss: () -> Unit, onSave: (CardioPiece) -> Unit) {
    var f by remember(edit) { mutableStateOf(CardioForm.fields(edit.piece, style)) }
    var problem by remember(edit) { mutableStateOf<String?>(null) }
    val l = CardioForm.labels(style)
    val parsed = CardioForm.parse(f, style, edit.piece)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit.fromPhoto && edit.problem == null) "Check the numbers" else "Log piece") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                edit.problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
                if (edit.fromPhoto && edit.problem == null) {
                    Hint("Read from your photo" + (edit.note.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") + ". Fix anything that's off, then save.")
                }
                l.distance?.let { unit ->
                    val type = if (style.unitM == 1.0) KeyboardType.Number else KeyboardType.Decimal
                    CardioField("Distance", f.distance, unit, type, { f = f.copy(distance = it); problem = null })
                }
                CardioField("Time", f.time, null, KeyboardType.Text, { f = f.copy(time = it); problem = null }, placeholder = "15:07")
                l.pace?.let { label ->
                    val computed = (parsed as? CardioForm.Ok)?.piece?.let { Cardio.split(it.durationS, it.distanceM) }
                    val shown = computed?.let { CardioForm.fields(CardioPiece(splitS = it), style).pace }
                    val type = if (style.pace.isSpeed) KeyboardType.Decimal else KeyboardType.Text
                    CardioField(label, f.pace, style.pace.label, type, { f = f.copy(pace = it); problem = null }, placeholder = shown)
                    if (f.pace.isBlank() && computed != null) Hint("Blank works it out from time and distance: ${style.pace.text(computed)}")
                }
                if (l.bestSplit || f.bestSplit.isNotBlank()) CardioField("Best split", f.bestSplit, "/500m", KeyboardType.Text, { f = f.copy(bestSplit = it); problem = null })
                if (l.watts || f.watts.isNotBlank()) CardioField("Avg power", f.watts, "W", KeyboardType.Number, { f = f.copy(watts = it); problem = null })
                CardioField("Calories", f.kcal, "kcal", KeyboardType.Number, { f = f.copy(kcal = it); problem = null })
                CardioField("Avg heart rate", f.hr, "bpm", KeyboardType.Number, { f = f.copy(hr = it); problem = null })
                problem?.let { Text(it, color = LocalStatusColors.current.warn, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val p = CardioForm.parse(f, style, edit.piece)) {
                    is CardioForm.Ok -> onSave(p.piece)
                    is CardioForm.Problem -> problem = p.message
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CardioField(label: String, value: String, suffix: String?, type: KeyboardType, onChange: (String) -> Unit, placeholder: String? = null) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, suffix = suffix?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = type),
    )
}
