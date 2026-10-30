package app.diurna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.diurna.R
import app.diurna.domain.Cardio
import app.diurna.domain.Metrics
import androidx.compose.runtime.remember
import app.diurna.domain.AminoAcids
import app.diurna.ui.theme.LocalStatusColors
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/** Lift rows shown before "All exercises". */
private const val TOP_LIFTS = 5

@Composable
fun MetricsScreen(vm: MetricsViewModel, contentPadding: PaddingValues, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val cardioDetail by vm.cardioDetail.collectAsStateWithLifecycle()
    val m = ui ?: return
    BackHandler(enabled = detail != null || cardioDetail != null, onBack = onClose)
    val padding = PaddingValues(
        start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 16.dp,
        bottom = contentPadding.calculateBottomPadding() + 16.dp,
    )
    detail?.let { ExerciseScreen(it, m.range, padding, vm, onClose); return }
    cardioDetail?.let { CardioScreen(it, m.range, padding, vm, onClose); return }
    var allLifts by rememberSaveable { mutableStateOf(false) }
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Metrics", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Hint("${m.from.short()} – ${m.to.short()}")
            }
        }
        item { RangeChips(m.range, vm::setRange) }
        item { WeightCardView(m.weight, m.showDots, m.from, m.to) }
        item { MoodCardView(m.mood, m.from, m.to) }
        m.macros?.let { card -> item { MacroCardView(card, m.from, m.to, vm::setMacroChartMode) } }
        m.carbs?.let { card -> item { CarbDetailCardView(card, m.from, m.to) } }
        m.fats?.let { card -> item { FatDetailCardView(card, m.from, m.to) } }
        m.lipids?.let { card -> item { LipidDetailCardView(card) } }
        m.aminos?.let { card -> item { AminoDetailCardView(card) } }
        item { TrainingCardView(m.training) }
        // Only once cardio has been logged; lifting-only Metrics is unchanged.
        m.cardio?.let { c -> item { CardioCardView(c, onOpen = vm::openCardio) } }
        item {
            LiftsCard(if (allLifts) m.lifts else m.lifts.take(TOP_LIFTS), more = m.lifts.size - TOP_LIFTS, expanded = allLifts,
                onToggle = { allLifts = !allLifts }, onOpen = vm::openExercise)
        }
    }
}

@Composable
private fun RangeChips(selected: Metrics.Range, onPick: (Metrics.Range) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Metrics.Range.entries.forEach { r -> FilterChip(r == selected, { onPick(r) }, label = { Text(r.label) }) }
    }
}

@Composable
private fun WeightCardView(w: WeightCard, showDots: Boolean, from: LocalDate, to: LocalDate) {
    SectionCard("Body weight", chip = { Hint("${w.logged} of ${w.days} days") }) {
        if (w.logged == 0) {
            Hint("No weigh-ins in this range. Add your weight on the Day screen's Morning card.")
            return@SectionCard
        }
        Headline(
            value = (w.latestAverage ?: w.latest)?.let { "%.1f".format(it) } ?: "—",
            unit = if (w.latestAverage != null) "lb · 7-day avg" else "lb · latest",
            delta = w.change?.let { "${arrow(it)} %.1f lb".format(abs(it)) },
            // Losing weight is shown as good; there's no goal direction to read yet.
            deltaColor = w.change?.let { if (it <= 0) LocalStatusColors.current.good else LocalStatusColors.current.warn },
        )
        val c = MaterialTheme.colorScheme
        LineChart(
            listOfNotNull(
                LineSeries(w.points, c.primaryContainer, line = false, dots = c.primary.copy(alpha = 0.45f)).takeIf { showDots },
                LineSeries(w.average, c.primary),
            ),
            description = "Body weight from ${from.short()} to ${to.short()}" +
                (w.change?.let { ", 7-day average ${if (it <= 0) "down" else "up"} %.1f lb".format(abs(it)) } ?: ""),
        )
        ChartDates(from.short(), to.short())
        if (w.average.all { it == null }) Hint("The 7-day average appears once a week has ${Metrics.AVG_MIN_POINTS} weigh-ins.")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            w.low?.let { Hint("Low %.1f · ${it.second.short()}".format(it.first)) }
            w.high?.let { Hint("High %.1f · ${it.second.short()}".format(it.first)) }
        }
    }
}

@Composable
private fun MoodCardView(card: MoodCard, from: LocalDate, to: LocalDate) {
    val sum = card.summary
    SectionCard("Mood & energy", chip = { Hint("${sum.rated} of ${sum.days} rated") }) {
        if (sum.rated == 0) {
            Hint("No check-ins in this range. Rate mood and energy on the Day screen in the evening.")
            return@SectionCard
        }
        val c = MaterialTheme.colorScheme
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Stat(sum.mood?.let { "%.1f".format(it) } ?: "—", "mood avg", c.primary)
            Stat(sum.energy?.let { "%.1f".format(it) } ?: "—", "energy avg", c.onSurface)
        }
        LineChart(
            listOf(
                LineSeries(card.energy, c.onSurface, width = 1.5.dp, dashed = true),
                LineSeries(card.mood, c.primary),
            ),
            description = "Mood and energy, 1 to 5, from ${from.short()} to ${to.short()}",
            yRange = 1.0..5.0,
        )
        ChartDates(from.short(), to.short())
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend("Mood", c.primary)
            Legend("Energy (dashed)", c.onSurface)
            Hint(if (card.smoothed) "7-day averages" else "Gaps are unrated days")
        }
    }
}

@Composable
private fun MacroCardView(
    card: MacroCard,
    from: LocalDate,
    to: LocalDate,
    onMode: (Metrics.MacroChartMode) -> Unit,
) {
    val chip = if (card.daysWithFood == 1) "1 day" else "${card.daysWithFood} days"
    SectionCard("Macros", chip = { Hint(chip) }) {
        if (card.daysWithFood == 0) {
            Hint("No food logged in this range.")
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metrics.MacroChartMode.entries.forEach { mode ->
                    FilterChip(mode == card.mode, { onMode(mode) }, label = { Text(mode.label) })
                }
            }
        }
        val avg = card.average
        val avgTotal = card.averageKcal
        if (avg != null && avgTotal != null) {
            val unit = if (card.daily) "kcal · daily avg" else "kcal · weekly avg"
            Headline(
                value = "%,d".format(avgTotal.roundToInt()),
                unit = unit,
                delta = null,
                deltaColor = null,
            )
            val pct = Metrics.macroPercent(avg)
            if (pct != null) {
                val fiberBit = if (avg.fiberKcal > 0) " · Fiber ${pct.fiberKcal.roundToInt()}%" else ""
                Hint(
                    "P ${pct.proteinKcal.roundToInt()}% · C ${pct.carbsKcal.roundToInt()}%$fiberBit · F ${pct.fatKcal.roundToInt()}%",
                )
            }
        }
        val c = MaterialTheme.colorScheme
        val fatColor = LocalStatusColors.current.warn
        // Fourth fiber segment only when any bar has fiber kcal, so basic P/C/F users see the same 3-segment chart.
        val showFiber = card.stacks.any { (it?.fiberKcal ?: 0.0) > 0.0 }
        val fiberColor = c.tertiary
        val colors = if (showFiber) listOf(c.primary, c.secondary, fiberColor, fatColor)
        else listOf(c.primary, c.secondary, fatColor)
        val shown = if (card.mode == Metrics.MacroChartMode.PERCENT) card.percentStacks else card.stacks
        val bars = shown.map { s ->
            s?.let {
                if (showFiber) StackBar(listOf(it.proteinKcal, it.carbsKcal, it.fiberKcal, it.fatKcal))
                else StackBar(listOf(it.proteinKcal, it.carbsKcal, it.fatKcal))
            }
        }
        val isPct = card.mode == Metrics.MacroChartMode.PERCENT
        val ref = card.targetKcal?.toDouble()?.takeIf { !isPct }
        val period = if (card.daily) "daily" else "weekly"
        val modeWord = if (isPct) "percent" else "calories"
        StackedBarChart(
            bars = bars,
            colors = colors,
            description = "Calories by macro, $period $modeWord from ${from.short()} to ${to.short()}" +
                (ref?.let { ", target %,d kcal".format(it.roundToInt()) } ?: ""),
            yMax = if (isPct) 100.0 else null,
            reference = ref,
            label = if (isPct) { v -> "${v.roundToInt()}%" } else { v ->
                if (v >= 1000) "%.1fk".format(v / 1000) else "${v.roundToInt()}"
            },
        )
        ChartDates(from.short(), to.short())
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend("Protein", c.primary)
            Legend("Carbs", c.secondary)
            if (showFiber) Legend("Fiber", fiberColor)
            Legend("Fat", fatColor)
            if (ref != null) Hint("Dashed = target")
            else if (!card.daily) Hint("Week averages")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CarbDetailCardView(card: CarbDetailCard, from: LocalDate, to: LocalDate) {
    SectionCard("Carbs", chip = { Hint(card.coverage.label) }) {
        val avg = card.average
        if (avg != null) {
            val unit = if (card.daily) "g · daily avg" else "g · weekly avg"
            Headline(
                value = "%.0f".format(avg.carbsG),
                unit = unit,
                delta = null,
                deltaColor = null,
            )
            Hint(carbAvgLine(avg, card.splitFiber, card.showAdded))
        }
        val c = MaterialTheme.colorScheme
        val warn = LocalStatusColors.current.warn
        val fiberColor = c.tertiary
        val starchColor = c.primary
        val sugarsColor = c.secondary
        val addedColor = warn
        val otherColor = c.outline
        // Bottom→top: fiber (split or total), starch, sugars, added (optional), other.
        val colors = buildList {
            if (card.splitFiber) { add(fiberColor.copy(alpha = 0.65f)); add(fiberColor) }
            else add(fiberColor)
            add(starchColor); add(sugarsColor)
            if (card.showAdded) add(addedColor)
            add(otherColor)
        }
        val bars = card.stacks.map { s ->
            s?.let {
                val segs = buildList {
                    if (card.splitFiber) {
                        if (it.splitFiber) {
                            add(it.solubleFiberG); add(it.insolubleFiberG)
                        } else {
                            // Unsplit day in a split-fiber range: keep fiber visible as the solid fiber color.
                            add(0.0)
                            add(it.fiberUnspecifiedG + it.solubleFiberG + it.insolubleFiberG)
                        }
                    } else {
                        add(it.fiberUnspecifiedG + it.solubleFiberG + it.insolubleFiberG)
                    }
                    add(it.starchG); add(it.sugarsG)
                    if (card.showAdded) add(it.addedSugarsG)
                    add(it.otherG)
                }
                StackBar(segs)
            }
        }
        val period = if (card.daily) "daily" else "weekly"
        StackedBarChart(
            bars = bars,
            colors = colors,
            description = "Carbohydrate breakdown in grams, $period from ${from.short()} to ${to.short()}. ${card.coverage.label}.",
            label = { v -> if (v >= 100) "${v.roundToInt()}" else "%.0f".format(v) },
        )
        ChartDates(from.short(), to.short())
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (card.splitFiber) {
                Legend("Soluble fiber", fiberColor.copy(alpha = 0.65f))
                Legend("Insoluble fiber", fiberColor)
            } else {
                Legend("Fiber", fiberColor)
            }
            Legend("Starch", starchColor)
            Legend("Sugars", sugarsColor)
            if (card.showAdded) Legend("Added", addedColor)
            Legend("Other", otherColor)
            if (!card.daily) Hint("Week averages")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FatDetailCardView(card: FatDetailCard, from: LocalDate, to: LocalDate) {
    SectionCard("Fats", chip = { Hint(card.coverage.label) }) {
        val avg = card.average
        if (avg != null) {
            val unit = if (card.daily) "g · daily avg" else "g · weekly avg"
            Headline(
                value = "%.0f".format(avg.fatG),
                unit = unit,
                delta = null,
                deltaColor = null,
            )
            Hint(
                "Sat %.0f · Mono %.0f · Poly %.0f · Trans %.0f · Other %.0f g".format(
                    avg.satG, avg.monoG, avg.polyG, avg.transG, avg.otherG,
                ),
            )
        }
        val c = MaterialTheme.colorScheme
        val warn = LocalStatusColors.current.warn
        val colors = listOf(warn, c.primary, c.secondary, c.error, c.outline)
        val bars = card.stacks.map { s ->
            s?.let { StackBar(listOf(it.satG, it.monoG, it.polyG, it.transG, it.otherG)) }
        }
        val period = if (card.daily) "daily" else "weekly"
        StackedBarChart(
            bars = bars,
            colors = colors,
            description = "Fat breakdown in grams, $period from ${from.short()} to ${to.short()}. ${card.coverage.label}.",
            label = { v -> if (v >= 100) "${v.roundToInt()}" else "%.0f".format(v) },
        )
        ChartDates(from.short(), to.short())
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Legend("Saturated", warn)
            Legend("Mono", c.primary)
            Legend("Poly", c.secondary)
            Legend("Trans", c.error)
            Legend("Other", c.outline)
            if (!card.daily) Hint("Week averages")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LipidDetailCardView(card: LipidDetailCard) {
    val s = card.summary
    // Summary-only card (no chart): up to five stats, so wrap instead of crushing a column.
    SectionCard("Omegas & cholesterol", chip = { Hint(Metrics.lipidCoverageLabel(s)) }) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            s.omega6to3?.let { Stat("%.1f".format(it), "ω6:3 avg") }
            s.avgCholesterolMg?.let { Stat("%.0f".format(it), "mg chol · avg") }
            s.avgOmega3G?.let { Stat("%.1f".format(it), "g ω3 · avg") }
            s.avgOmega6G?.let { Stat("%.1f".format(it), "g ω6 · avg") }
            s.avgOmega9G?.let { Stat("%.1f".format(it), "g ω9 · avg") }
        }
    }
}

@Composable
private fun AminoDetailCardView(card: AminoDetailCard) {
    val s = card.summary
    val avg = s.average
    val refs = card.refs
    var expanded by rememberSaveable { mutableStateOf(false) }
    SectionCard("Amino acids", chip = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Hint(s.coverageLabel)
            if (s.daysWithPartial > 0) {
                Hint("partial")
            }
        }
    }) {
        val primary = AminoAcids.PRIMARY_BARS.mapNotNull { (label, pick) ->
            pick(avg)?.let { label to it }
        }
        if (primary.isEmpty()) {
            Hint("No amino data in this range.")
            return@SectionCard
        }
        val refMax = refs?.let { r ->
            primary.mapNotNull { (label, _) -> r.forPrimaryLabel(label) }.maxOrNull() ?: 0
        } ?: 0
        val maxMg = maxOf(primary.maxOf { it.second }, refMax).toDouble().coerceAtLeast(1.0)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            primary.forEach { (label, mg) ->
                AminoBarRow(label, mg, refs?.forPrimaryLabel(label), maxMg, wideLabel = label.contains('+'))
            }
            if (expanded) {
                val detail = AminoAcids.PAIR_DETAIL.mapNotNull { (label, pick) ->
                    pick(avg)?.let { label to it }
                }
                if (detail.isNotEmpty()) {
                    Hint("Pair members")
                    detail.forEach { (label, mg) ->
                        AminoBarRow(label, mg, refs?.forDetailLabel(label), maxMg, wideLabel = false)
                    }
                }
            }
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (expanded) "Hide Met/Cys/Phe/Tyr" else "Show Met, Cys, Phe, Tyr")
            }
            when {
                refs != null && card.weightLb != null ->
                    Hint(
                        "Dashed marks: WHO/FAO/UNU 2007 adult average requirements " +
                            "(TRS 935 Table 23) at %.0f lb — reference lines, not targets.".format(card.weightLb),
                    )
                else ->
                    Hint("Log a body weight to show WHO adult amino requirement reference lines.")
            }
        }
    }
}

@Composable
private fun AminoBarRow(
    label: String,
    mg: Int,
    refMg: Int?,
    maxMg: Double,
    wideLabel: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.width(if (wideLabel) 64.dp else 36.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
        )
        val frac = (mg.toDouble() / maxMg).toFloat().coerceIn(0f, 1f)
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(frac)
                    .background(MaterialTheme.colorScheme.primary),
            )
            if (refMg != null && refMg > 0) {
                val refFrac = (refMg.toDouble() / maxMg).toFloat().coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(refFrac),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)),
                    )
                }
            }
        }
        Text(
            "%,d mg".format(mg),
            Modifier.width(72.dp),
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}


private fun carbAvgLine(avg: Metrics.CarbStack, splitFiber: Boolean, showAdded: Boolean): String {
    val parts = buildList {
        if (splitFiber) {
            add("Sol %.0f".format(avg.solubleFiberG))
            add("Insol %.0f".format(avg.insolubleFiberG))
        } else {
            val fiber = avg.fiberUnspecifiedG + avg.solubleFiberG + avg.insolubleFiberG
            if (fiber > 0) add("Fiber %.0f".format(fiber))
        }
        if (avg.starchG > 0) add("Starch %.0f".format(avg.starchG))
        if (avg.sugarsG > 0 || showAdded) add("Sugars %.0f".format(avg.sugarsG + avg.addedSugarsG))
        if (showAdded && avg.addedSugarsG > 0) add("Added %.0f".format(avg.addedSugarsG))
        if (avg.otherG > 0) add("Other %.0f".format(avg.otherG))
    }
    return parts.joinToString(" · ") + " g"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainingCardView(t: TrainingCard) {
    SectionCard("Training", chip = { Hint("${t.workouts} workouts") }) {
        if (t.workouts == 0) {
            Hint("No lifting logged in this range.")
            return@SectionCard
        }
        val s = LocalStatusColors.current
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Stat(volume(t.thisWeek), "lb this week")
            t.vsEarlier?.let { Stat("${if (it >= 0) "+" else "−"}${(abs(it) * 100).roundToInt()}%", "vs earlier weeks", if (it >= 0) s.good else s.warn) }
            Stat("${t.prs}", if (t.prs == 1) "PR" else "PRs")
        }
        BarChart(
            t.weeks.map { it.volume },
            description = "Weekly volume, ${t.weeks.size} weeks, this week ${volume(t.thisWeek)} lb",
            label = ::volume,
        )
        t.weeks.firstOrNull()?.let { ChartDates(it.monday.short(), "This week") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardioCardView(c: CardioCard, onOpen: (Long) -> Unit) {
    SectionCard("Cardio", chip = { Hint(if (c.sessions == 1) "1 session" else "${c.sessions} sessions") }) {
        if (c.sessions == 0) {
            Hint("No cardio logged in this range.")
            return@SectionCard
        }
        val unitText = { v: Double -> if (c.unit == "m") metres(v) else if (c.unit == "min") "${v.roundToInt()}" else "%.1f".format(v) }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Stat(if (c.unit == "m") "%,d".format(c.thisWeek.roundToInt()) else unitText(c.thisWeek), "${c.unit} this week")
            Stat("${c.prs}", if (c.prs == 1) "PR" else "PRs")
        }
        BarChart(
            c.weekly,
            description = "Weekly cardio ${if (c.unit == "min") "time" else "distance"}, ${c.weeks.size} weeks, this week ${unitText(c.thisWeek)} ${c.unit}",
            label = unitText,
        )
        c.weeks.firstOrNull()?.let { ChartDates(it.monday.short(), "This week") }
        Column {
            c.rows.forEach { r ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClickLabel = "Open ${r.name}") { onOpen(r.exerciseId) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.bodyLarge)
                        if (r.last.isNotEmpty()) Hint(r.last)
                    }
                    Sparkline(r.spark, MaterialTheme.colorScheme.primary, Modifier.size(width = 96.dp, height = 28.dp))
                }
            }
        }
    }
}

@Composable
private fun CardioScreen(d: CardioDetail, range: Metrics.Range, padding: PaddingValues, vm: MetricsViewModel, onClose: () -> Unit) {
    val label = d.metric.label(d.style)
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) {
                    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Back")
                }
                Text(d.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                d.metrics.forEach { m -> FilterChip(m == d.metric, { vm.setCardioMetric(m) }, label = { Text(m.label(d.style)) }) }
            }
        }
        item { RangeChips(range, vm::setRange) }
        item {
            val s = LocalStatusColors.current
            val lower = d.metric.lowerIsBetter(d.style)
            val better = d.change?.let { if (lower) it <= 0 else it >= 0 }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Headline(
                    value = d.current?.let { cardioValue(d.metric, d.style, it) } ?: "—",
                    unit = if (d.metric == CardioMetric.PACE || d.metric == CardioMetric.POWER) "avg, last session" else "last session",
                    delta = d.change?.let {
                        val mag = when (d.metric) {
                            CardioMetric.PACE -> if (lower) "%.1fs".format(abs(it)) else "%.1f".format(abs(it))
                            CardioMetric.DISTANCE -> if (d.style.unitM == 1.0) "%,d".format(abs(it).roundToInt()) else "%.1f".format(abs(it))
                            else -> "%,d".format(abs(it).roundToInt())
                        }
                        "${if (lower) (if (it <= 0) "faster" else "slower") else arrow(it)} $mag"
                    },
                    deltaColor = better?.let { if (it) s.good else s.warn },
                )
                if (d.values.any { it != null }) {
                    val c = MaterialTheme.colorScheme
                    LineChart(
                        listOf(LineSeries(d.values, c.primary, dots = c.primary)),
                        description = "${d.name} $label per session",
                        height = 170.dp,
                        label = when (d.metric) {
                            CardioMetric.DISTANCE -> if (d.style.unitM == 1.0) ::metres else { v -> "%.1f".format(v) }
                            CardioMetric.PACE -> if (lower) { v -> Cardio.clock(v) } else { v -> "%.1f".format(v) }
                            else -> { v -> "${v.roundToInt()}" }
                        },
                    )
                    ChartDates(d.first?.short() ?: "", d.last?.short() ?: "")
                    if (lower) Hint("Lower is faster.")
                } else {
                    Hint("No ${label.lowercase()} logged in this range.")
                }
            }
        }
        if (d.records.isNotEmpty()) item {
            SectionCard("Records") {
                Column { d.records.forEach { RecordRow(it.label, it.value, it.date) } }
            }
        }
        item {
            SectionCard("Sessions", chip = { Hint(label) }) {
                Column {
                    d.sessions.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Hint(s.date.short(), Modifier.width(52.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.text, style = MaterialTheme.typography.bodyMedium)
                                s.format?.let { Hint(it) }
                            }
                            Text(
                                if (s.pr) "${s.value} ★" else s.value, fontWeight = FontWeight.SemiBold,
                                color = if (s.pr) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Hint(
                    if (d.style.hasDistance) "★ a PR: farther, faster or more power than every earlier session of the same length. The first of each is the baseline."
                    else "★ a PR: more calories or power than every earlier session of the same length. The first of each is the baseline."
                )
            }
        }
    }
}

@Composable
private fun LiftsCard(rows: List<LiftRow>, more: Int, expanded: Boolean, onToggle: () -> Unit, onOpen: (Long) -> Unit) {
    SectionCard("Lifts", chip = { Hint("e1RM") }) {
        if (rows.isEmpty()) {
            Hint("No exercises logged in this range.")
            return@SectionCard
        }
        Column {
            rows.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                LiftRowView(r) { onOpen(r.exerciseId) }
            }
        }
        if (more > 0) {
            TextButton(onToggle, Modifier.fillMaxWidth()) { Text(if (expanded) "Fewer exercises" else "All exercises ($more more)") }
        }
    }
}

@Composable
private fun LiftRowView(r: LiftRow, onClick: () -> Unit) {
    val s = LocalStatusColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClickLabel = "Open ${r.name}", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.name, style = MaterialTheme.typography.bodyLarge)
            Hint(listOfNotNull(r.best, r.e1rm?.let { "e1RM ${it.roundToInt()}" }).joinToString(" · "))
        }
        Sparkline(r.spark, MaterialTheme.colorScheme.primary, Modifier.size(width = 96.dp, height = 28.dp))
        val ch = r.change?.roundToInt()
        Text(
            when { ch == null -> ""; ch > 0 -> "+$ch"; ch < 0 -> "−${-ch}"; else -> "±0" },
            Modifier.width(44.dp), fontWeight = FontWeight.SemiBold,
            color = if ((ch ?: 0) > 0) s.good else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExerciseScreen(d: ExerciseDetail, range: Metrics.Range, padding: PaddingValues, vm: MetricsViewModel, onClose: () -> Unit) {
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) {
                    Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Back")
                }
                Text(d.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiftMetric.entries.forEach { m -> FilterChip(m == d.metric, { vm.setMetric(m) }, label = { Text(m.label) }) }
            }
        }
        item { RangeChips(range, vm::setRange) }
        item {
            val unit = if (d.metric == LiftMetric.E1RM) "lb estimated 1RM" else "lb last session"
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Headline(
                    value = d.current?.let { if (d.metric == LiftMetric.E1RM) "${it.roundToInt()}" else "%,d".format(it.roundToInt()) } ?: "—",
                    unit = unit,
                    delta = d.change?.let { "${arrow(it)} ${abs(it).roundToInt()}" },
                    deltaColor = d.change?.let { if (it >= 0) LocalStatusColors.current.good else LocalStatusColors.current.warn },
                )
                if (d.values.any { it != null }) {
                    val c = MaterialTheme.colorScheme
                    LineChart(
                        listOf(LineSeries(d.values, c.primary, dots = c.primary)),
                        description = "${d.name} ${d.metric.label} per session",
                        height = 170.dp,
                        label = if (d.metric == LiftMetric.E1RM) { v -> "${v.roundToInt()}" } else ::volume,
                    )
                    ChartDates(d.first?.short() ?: "", d.last?.short() ?: "")
                } else {
                    Hint("No sessions in this range.")
                }
                if (d.metric == LiftMetric.E1RM) Hint("Estimated from the best set: weight × (1 + reps/30), sets of up to ${Metrics.E1RM_MAX_REPS} reps.")
            }
        }
        item {
            SectionCard("Records") {
                Column {
                    d.bestE1rm?.let { RecordRow("Best e1RM", "${it.first.roundToInt()}", it.second) }
                    d.heaviest?.let { RecordRow("Heaviest set", it.first, it.second) }
                    d.bestVolume?.let { RecordRow("Best session volume", "%,d lb".format(it.first.roundToInt()), it.second) }
                }
            }
        }
        item {
            SectionCard("Sessions", chip = { Hint(d.metric.label) }) {
                Column {
                    d.sessions.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Hint(s.date.short(), Modifier.width(52.dp))
                            Text(s.sets, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            val v = if (d.metric == LiftMetric.E1RM) s.e1rm?.roundToInt()?.toString() ?: "—" else volume(s.volume)
                            Text(
                                if (s.pr) "$v ★" else v, fontWeight = FontWeight.SemiBold,
                                color = if (s.pr) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                Hint("★ a PR: best e1RM or heaviest weight so far.")
            }
        }
    }
}

@Composable
private fun RecordRow(label: String, value: String, date: LocalDate) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
        Hint(" ${date.short()}")
    }
}

@Composable
private fun Headline(value: String, unit: String, delta: String?, deltaColor: Color?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Hint(unit, Modifier.padding(bottom = 4.dp).weight(1f))
        if (delta != null) Text(delta, Modifier.padding(bottom = 4.dp), color = deltaColor ?: Color.Unspecified, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color = Color.Unspecified) {
    // softWrap=false keeps a squeezed column from stacking one character per line (tall empty card).
    Column {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 16.dp, height = 3.dp)) { drawRect(color) }
        Hint(label)
    }
}

private fun arrow(delta: Double) = if (delta < 0) "▼" else "▲"

/** 12,400 m → "12.4k"; short distances stay whole. */
private fun metres(v: Double): String = volume(v)

/** 41,200 → "41.2k"; small numbers stay whole. */
private fun volume(v: Double): String = if (v >= 1000) "%.1fk".format(v / 1000) else "${v.roundToInt()}"
