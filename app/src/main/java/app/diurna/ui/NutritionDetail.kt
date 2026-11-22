package app.diurna.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.diurna.R
import app.diurna.data.FoodEntryEntity
import app.diurna.domain.DayNutrition
import app.diurna.domain.DvKind
import app.diurna.domain.DvNutrient
import app.diurna.domain.MacroEnergy
import app.diurna.domain.MicronutrientProfile
import app.diurna.domain.NutritionTotals
import app.diurna.ui.theme.LocalStatusColors
import kotlin.math.roundToInt

/** "1,480", "31", "4.2", "0". */
internal fun nutrientAmount(v: Double): String = when {
    v >= 100 -> "%,d".format(v.roundToInt())
    v >= 10 || v == 0.0 -> v.roundToInt().toString()
    else -> "%.1f".format(v)
}

/** A day's estimated entries as [DayNutrition] inputs. */
internal fun dayNutrition(food: List<FoodEntryEntity>, micros: Map<Long, MicronutrientProfile>, dv: Map<DvNutrient, Double>): DayNutrition? =
    DayNutrition.of(
        food.filter { it.estimatedAt != null }.map { f ->
            DayNutrition.Entry(
                f.id, f.time, f.text.lines().first(),
                DayNutrition.amounts(f.fiberG, f.satFatG, f.addedSugarsG, micros[f.id] ?: MicronutrientProfile()),
            )
        },
        dv,
    )

@Composable
private fun GroupLabel(text: String) =
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)

@Composable
private fun AmountLine(label: String, value: String, indent: Boolean = false, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 14.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            color = if (indent) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        )
        Text(
            value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = if (color != Color.Unspecified) color else if (indent) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
        )
    }
}

private fun pair(a: Int?, b: Int?, unit: String): String? = when {
    a != null && b != null -> "$a · $b $unit"
    else -> null
}

// ---------------------------------------------------------------- one entry

/**
 * An open food row's detail: macro tiles with the energy split, then carbs, fats, vitamins and
 * minerals as labelled sections, and amino acids folded to one line. Only known values show.
 */
@Composable
internal fun EntryNutritionDetail(f: FoodEntryEntity, micros: MicronutrientProfile?, dv: Map<DvNutrient, Double>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MacroTiles(f)
        val carbs = buildList {
            f.fiberG?.let { add(Triple("Fiber", "$it g", false)) }
            pair(f.solubleFiberG, f.insolubleFiberG, "g")?.let { add(Triple("Soluble · Insoluble", it, true)) }
                ?: run {
                    f.solubleFiberG?.let { add(Triple("Soluble", "$it g", true)) }
                    f.insolubleFiberG?.let { add(Triple("Insoluble", "$it g", true)) }
                }
            f.sugarsG?.let { add(Triple("Sugars", "$it g", false)) }
            f.addedSugarsG?.let { add(Triple(if (f.sugarsG != null) "Added" else "Added sugars", "$it g", f.sugarsG != null)) }
            f.starchG?.let { add(Triple("Starch", "$it g", false)) }
        }
        val fats = buildList {
            f.satFatG?.let { add("Saturated" to "$it g") }
            pair(f.monoFatG, f.polyFatG, "g")?.let { add("Mono · Poly" to it) } ?: run {
                f.monoFatG?.let { add("Mono" to "$it g") }
                f.polyFatG?.let { add("Poly" to "$it g") }
            }
            f.transFatG?.let { add("Trans" to "$it g") }
            f.cholesterolMg?.let { add("Cholesterol" to "$it mg") }
            listOfNotNull(f.omega3G?.let { "Ω3 $it" }, f.omega6G?.let { "Ω6 $it" }, f.omega9G?.let { "Ω9 $it" })
                .takeIf { it.isNotEmpty() }?.let { add("Omega" to it.joinToString(" · ") + " g") }
        }
        val minerals = DvNutrient.entries.mapNotNull { n -> n.micro?.let { id -> micros?.get(id)?.let { n to it } } }
        var first = true
        @Composable fun divider() { if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outline); first = false }
        if (carbs.isNotEmpty()) {
            divider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GroupLabel("CARBS")
                carbs.forEach { (l, v, indent) -> AmountLine(l, v, indent) }
            }
        }
        if (fats.isNotEmpty()) {
            divider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GroupLabel("FATS")
                fats.forEach { (l, v) -> AmountLine(l, v) }
            }
        }
        if (minerals.isNotEmpty()) {
            divider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GroupLabel("VITAMINS & MINERALS")
                // Two columns; a limit that one entry already half-fills is flagged.
                val warn = LocalStatusColors.current.warn
                val heavy = minerals.filter { (n, v) -> n.kind == DvKind.LIMIT && (dv[n] ?: 0.0) > 0 && v / dv.getValue(n) >= 0.5 }
                minerals.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { (n, v) ->
                            Box(Modifier.weight(1f)) {
                                AmountLine(n.label, "${nutrientAmount(v)} ${n.unit}", color = if (heavy.any { it.first == n }) warn else Color.Unspecified)
                            }
                        }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
                heavy.forEach { (n, v) ->
                    Hint("${n.label} is ${(v / dv.getValue(n) * 100).roundToInt()}% of a day's limit in this one entry.")
                }
            }
        }
        val aminos = listOfNotNull(
            f.hisMg?.let { "His" to it }, f.ileMg?.let { "Ile" to it }, f.leuMg?.let { "Leu" to it },
            f.lysMg?.let { "Lys" to it }, f.metMg?.let { "Met" to it }, f.pheMg?.let { "Phe" to it },
            f.thrMg?.let { "Thr" to it }, f.trpMg?.let { "Trp" to it }, f.valMg?.let { "Val" to it },
            f.cysMg?.let { "Cys" to it }, f.tyrMg?.let { "Tyr" to it },
        )
        if (aminos.isNotEmpty()) {
            divider()
            var open by remember(f.id) { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .clickable(onClickLabel = if (open) "Hide amino acids" else "Show amino acids") { open = !open },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        GroupLabel("AMINO ACIDS")
                        val leu = f.leuMg?.let { "Leucine ${nutrientAmount(it / 1000.0)} g · " }.orEmpty()
                        if (!open) Hint("$leu${aminos.size} measured")
                    }
                    Chevron(open)
                }
                if (open) {
                    aminos.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            row.forEach { (l, mg) -> Box(Modifier.weight(1f)) { AmountLine(l, "$mg mg") } }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        IngredientMatchNote(f)
    }
}

@Composable
private fun MacroTiles(f: FoodEntryEntity) {
    val p = f.proteinG ?: return
    val c = f.carbsG ?: return
    val fat = f.fatG ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Protein" to p, "Carbs" to c, "Fat" to fat).forEach { (label, g) ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("$g g", style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
                }
            }
        }
        val e = MacroEnergy.kcal(p, c, fat, f.fiberG, f.solubleFiberG, f.insolubleFiberG)
        if (e.total > 0) {
            val shares = listOf(e.proteinKcal, e.carbsKcal + e.fiberKcal, e.fatKcal).map { it / e.total }
            val pct = shares.map { (it * 100).roundToInt() }
            val colors = listOf(MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primary, Color(0xFF6D5BA8))
            val text = "Energy: ${pct[0]}% protein · ${pct[1]}% carbs · ${pct[2]}% fat"
            Row(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).semantics { contentDescription = text },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                shares.forEachIndexed { i, s ->
                    if (s > 0) Box(Modifier.weight(s.toFloat()).fillMaxHeight().background(colors[i]))
                }
            }
            Hint(text)
        }
    }
}

@Composable
private fun IngredientMatchNote(f: FoodEntryEntity) {
    val matched = f.aminoMatched
    val total = f.aminoTotal
    val list = f.ingredientsJson?.let { app.diurna.data.fdc.IngredientsJson.decode(it) }
    val text = when {
        matched != null && total != null && total > 0 && matched < total -> {
            val missed = list?.filter { it.fdcId == null }?.joinToString(", ") { it.name }.orEmpty()
            "Matched $matched of $total ingredients in USDA data." +
                (if (missed.isNotEmpty()) " Not matched: $missed, so amino acids are a little low." else "")
        }
        matched != null && total != null && total > 0 -> "Matched all $total ingredients in USDA data."
        !list.isNullOrEmpty() -> "Ingredients: " + list.joinToString("; ") { ing ->
            val match = ing.matchedDescription?.let { " → $it" } ?: ""
            "${ing.name} (${"%.0f".format(ing.grams)} g)$match"
        }
        else -> return
    }
    Hint(text)
}

@Composable
private fun Chevron(open: Boolean, size: Int = 18) {
    val angle by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Icon(
        painterResource(R.drawable.ic_chevron_down), contentDescription = null,
        Modifier.size(size.dp).rotate(angle), tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------- the day

private enum class Group { GOALS, LIMITS, BREAKDOWN }

/**
 * The Food card's drill-down: one summary line, then three folded groups, then a group's rows,
 * then a row's sources. Nothing is open to start with, and none of it is remembered once the
 * card leaves the screen; only the amounts / % choice is ([percent]).
 */
@Composable
internal fun DayNutritionDetail(
    day: DayNutrition,
    totals: NutritionTotals,
    /** Entries with any vitamin or mineral value. */
    withMicros: Int,
    percent: Boolean,
    onPercent: (Boolean) -> Unit,
    resetKey: Any,
) {
    var open by remember(resetKey) { mutableStateOf(false) }
    var group by remember(resetKey) { mutableStateOf<Group?>(null) }
    var row by remember(resetKey) { mutableStateOf<DvNutrient?>(null) }
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (open) c.surfaceContainerHigh else c.surface,
        border = if (open) null else BorderStroke(1.dp, c.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(onClickLabel = if (open) "Collapse" else "Expand") { open = !open }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Nutrition detail", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    SummaryLine(day)
                }
                Chevron(open)
            }
            if (open) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!percent, { onPercent(false) }, label = { Text("Amounts") })
                        FilterChip(percent, { onPercent(true) }, label = { Text("% Daily Value") })
                    }
                    if (withMicros < day.total) {
                        Hint(
                            if (withMicros == 0) "No entry has vitamin or mineral data yet; those rows wait for a scanned or USDA-matched food."
                            else "Vitamins and minerals come from $withMicros of ${day.total} entries, so their totals are a floor.",
                        )
                    }
                    GroupCard("Get enough", AnnotatedString(day.goalsPeek), warn = false, open = group == Group.GOALS, onToggle = { group = if (group == Group.GOALS) null else Group.GOALS }) {
                        day.goals.forEach { r -> NutrientRow(r, percent, open = row == r.nutrient) { row = if (row == r.nutrient) null else r.nutrient } }
                    }
                    GroupCard("Keep under", limitsPeek(day), warn = false, open = group == Group.LIMITS, onToggle = { group = if (group == Group.LIMITS) null else Group.LIMITS }) {
                        day.limits.forEach { r -> NutrientRow(r, percent, open = row == r.nutrient) { row = if (row == r.nutrient) null else r.nutrient } }
                    }
                    val breakdown = breakdown(totals)
                    if (breakdown.isNotEmpty()) {
                        GroupCard(
                            "Breakdown", AnnotatedString(breakdown.take(3).joinToString(" · ") { "${it.first} ${it.second}" }), warn = false,
                            open = group == Group.BREAKDOWN, onToggle = { group = if (group == Group.BREAKDOWN) null else Group.BREAKDOWN },
                        ) {
                            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                breakdown.chunked(2).forEach { pair ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                        pair.forEach { (l, v) -> Box(Modifier.weight(1f)) { AmountLine(l, v, indent = true) } }
                                        if (pair.size == 1) Box(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(day: DayNutrition) {
    val s = day.summary
    if (s == null) {
        Hint("No detail yet")
        return
    }
    // The over part reads in the warning colour; the rest stays muted.
    val (head, tail) = s.split(" · ", limit = 2).let { it[0] to it.getOrNull(1) }
    Row {
        Hint(head + if (tail != null) " · " else "")
        if (tail != null) {
            Text(
                tail, style = MaterialTheme.typography.bodySmall,
                color = if (day.limits.any { it.reached }) LocalStatusColors.current.warn else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The limits preview with only the ones past 100% in the warning colour. */
@Composable
private fun limitsPeek(day: DayNutrition): AnnotatedString {
    val warn = LocalStatusColors.current.warn
    val known = day.limits.filter { it.amount != null }
    if (known.isEmpty()) return AnnotatedString(day.limitsPeek)
    return buildAnnotatedString {
        known.forEachIndexed { i, r ->
            if (i > 0) append(" · ")
            val text = "${r.nutrient.label} ${r.pct}%"
            if (r.reached) withStyle(SpanStyle(color = warn)) { append(text) } else append(text)
        }
    }
}

@Composable
private fun GroupCard(title: String, peek: AnnotatedString, warn: Boolean, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable(onClickLabel = if (open) "Collapse $title" else "Expand $title", onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary)
                    Text(
                        peek, style = MaterialTheme.typography.bodySmall,
                        color = if (warn) LocalStatusColors.current.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Chevron(open, 16)
            }
            if (open) Column(Modifier.padding(bottom = 4.dp)) { content() }
        }
    }
}

@Composable
private fun NutrientRow(r: DayNutrition.Row, percent: Boolean, open: Boolean, onToggle: () -> Unit) {
    val status = LocalStatusColors.current
    val c = MaterialTheme.colorScheme
    val n = r.nutrient
    val limit = n.kind == DvKind.LIMIT
    // Goals turn green once reached; limits are grey until they pass 100%, then orange.
    val tone = when {
        limit && r.reached -> status.warn
        limit -> Color(0xFF8B8B98)
        r.reached -> status.good
        else -> c.primary
    }
    val amount = r.amount?.let { "${nutrientAmount(it)} ${n.unit}" }
    val main = when {
        r.amount == null -> "—"
        percent -> "${r.pct}%"
        else -> amount!!
    }
    val coverage = if (r.partial) " · ${r.known} of ${r.total} entries" else ""
    val sub = when {
        r.amount == null -> "no data"
        percent -> "$amount$coverage"
        else -> "${r.pct}% DV$coverage"
    }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = r.sources.isNotEmpty(), onClickLabel = if (open) "Hide sources" else "Show where it came from", onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Name and value on one line, the detail under it, so a long name never squeezes.
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(n.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Text(
                main, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Medium, maxLines = 1,
                color = if (percent && r.amount != null && r.reached) tone else Color.Unspecified,
            )
        }
        if (percent && r.amount != null) {
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceContainerHighest)) {
                Box(Modifier.fillMaxWidth(((r.pct ?: 0) / 100f).coerceIn(0f, 1f)).fillMaxHeight().background(tone))
            }
        }
    }
    if (open && r.sources.isNotEmpty()) {
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)).background(c.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            r.sources.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.time, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = c.onSurfaceVariant)
                    Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("${nutrientAmount(s.amount)} ${n.unit}", style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}

/** Amount-only lines with no Daily Value; cholesterol lives here (no current US limit). */
private fun breakdown(t: NutritionTotals): List<Pair<String, String>> = listOfNotNull(
    t.sugarsG?.let { "Sugars" to "$it g" },
    t.starchG?.let { "Starch" to "$it g" },
    t.solubleFiberG?.let { "Soluble fiber" to "$it g" },
    t.insolubleFiberG?.let { "Insoluble fiber" to "$it g" },
    t.monoFatG?.let { "Mono fat" to "$it g" },
    t.polyFatG?.let { "Poly fat" to "$it g" },
    t.transFatG?.let { "Trans fat" to "$it g" },
    t.cholesterolMg?.let { "Cholesterol" to "$it mg" },
    t.omega3G?.let { "Omega-3" to "$it g" },
    t.omega6G?.let { "Omega-6" to "$it g" },
)
