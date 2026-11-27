package app.diurna.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.diurna.domain.SleepMetrics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.diurna.R
import app.diurna.ui.theme.LocalStatusColors
import app.diurna.data.SleepPeriodEntity
import app.diurna.domain.HealthInventory
import app.diurna.domain.SleepJson
import app.diurna.domain.SleepRules
import app.diurna.domain.SleepView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Stage colours: deeper sleep is a deeper purple; awake is pale grey. */
private object StageColors {
    val Awake = Color(0xFFB4B4C0)
    val Rem = Color(0xFFC4B5FD)
    val Light = Color(0xFF8B5CF6)
    val Deep = Color(0xFF5B21B6)
}

/** Rows of the stage chart, top to bottom; null for codes with no row (unknown). */
private fun level(code: Int): Int? = when (code) {
    SleepRules.AWAKE, SleepRules.OUT_OF_BED, SleepRules.AWAKE_IN_BED -> 0
    SleepRules.REM -> 1
    SleepRules.LIGHT, SleepRules.SLEEPING -> 2
    SleepRules.DEEP -> 3
    else -> null
}

private fun color(code: Int): Color = when (level(code)) {
    0 -> StageColors.Awake
    1 -> StageColors.Rem
    3 -> StageColors.Deep
    else -> StageColors.Light
}

private val ReadAt: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
private val NightOf: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")

/** The Morning card's Sleep row: tap it for the night's detail. */
@Composable
fun SleepRow(ui: DayViewModel.SleepUi, date: LocalDate, onOpen: () -> Unit) {
    val row = SleepView.row(ui.day, date, ui.today)
    val main = ui.day.main
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !ui.day.empty, onClickLabel = "Open sleep detail", onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Sleep", Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (ui.day.empty) {
                Hint(row.title + if (date >= ui.today.minusDays(1)) ". ${source(ui.origin)} sends last night after its app syncs." else "")
            } else {
                Text(row.title, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                if (main != null && main.stages != null) StageStrip(main, Modifier.fillMaxWidth().height(8.dp))
                row.detail?.let { Hint(it + (ui.origin?.let { o -> " · " + HealthInventory.sourceName(o) } ?: "")) }
            }
        }
        if (!ui.day.empty) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun source(origin: String?) = origin?.let { HealthInventory.sourceName(it) } ?: "Your ring or watch"

/** One line of colour per stage across the night, with the woke → up tail faded. */
@Composable
private fun StageStrip(n: SleepPeriodEntity, modifier: Modifier) {
    val stages = SleepJson.parseStages(n.stages)
    val length = SleepView.lengthS(n).toFloat()
    val wokeS = n.timeInBedS.toFloat()
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        if (length <= 0f) return@Canvas
        drawRect(track)
        stages.forEach { s ->
            if (level(s.code) == null) return@forEach
            val x0 = size.width * s.startS / length
            val x1 = size.width * s.endS / length
            val faded = s.startS >= wokeS && n.wokeAt != null
            drawRect(color(s.code).copy(alpha = if (faded) 0.3f else 1f), Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(1f), size.height))
        }
    }
}

/** A night in full: stages, totals, and heart rate and HRV through the night. */
@Composable
fun SleepScreen(ui: DayViewModel.SleepUi, date: LocalDate, padding: PaddingValues, onClose: () -> Unit) {
    val n = ui.day.main
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Back") }
                Column {
                    Text("Night to ${date.format(NightOf)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    val read = (n ?: ui.day.naps.firstOrNull())?.fetchedAt?.let {
                        " · read " + ReadAt.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                    } ?: ""
                    Hint(source(ui.origin) + " via Health Connect" + read)
                }
            }
        }
        if (n != null) {
            item { NightCard(n) }
            if (n.totalSleepS != null) item { StagesCard(n) }
            val hr = SleepJson.parsePoints(n.hrSamples)
            val hrv = SleepJson.parsePoints(n.hrvSamples)
            if (hr.isNotEmpty() || n.avgHr != null) item { HeartCard(n, hr) }
            if (hrv.isNotEmpty() || n.avgHrv != null) item { HrvCard(n, hrv) }
        }
        ui.day.naps.forEach { nap -> item { NapCard(nap) } }
        item {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    "Worked out from the stages ${source(ui.origin)} writes to Health Connect, in 5-minute steps, " +
                        "so totals can differ from its own app by a few minutes.",
                )
                Hint(
                    "Breathing rate and SpO2 show when a device writes them; Oura doesn't. " +
                        "Scores, readiness and temperature need the Oura connection, which comes later.",
                )
            }
        }
    }
}

@Composable
private fun NightCard(n: SleepPeriodEntity) {
    SectionCard("Night") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                SleepView.asleep(n) + if (n.totalSleepS != null) " · ${SleepView.hm(n.timeInBedS)} in bed" else "",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            Hint(SleepView.times(n))
        }
        if (n.stages != null) Hypnogram(n)
        SleepView.awakeAfterWaking(n)?.let { Hint("Awake ${SleepView.hm(it)} more after waking, before getting up; left out of time in bed.") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            n.latencyS?.let { Figure(SleepView.hm(it), "fell asleep after", Modifier.weight(1f)) }
            n.efficiency?.let { Figure("${(it * 100).roundToInt()}%", "efficiency", Modifier.weight(1f)) }
            n.awakenings?.let { Figure("$it", if (it == 1) "wake" else "wakes", Modifier.weight(1f)) }
        }
        Hint("Efficiency is asleep ÷ bed to woke. Wakes count 10 minutes or more awake; shorter stirs show in the chart only.")
    }
}

@Composable
private fun Figure(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Hint(label)
    }
}

/** Stages as steps over four rows (awake, REM, light, deep), with clock times under it. */
@Composable
private fun Hypnogram(n: SleepPeriodEntity) {
    val stages = SleepJson.parseStages(n.stages)
    val length = SleepView.lengthS(n).toFloat()
    val wokeS = n.timeInBedS.toFloat()
    val grid = MaterialTheme.colorScheme.outline
    val labels = listOf("Awake", "REM", "Light", "Deep")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.height(120.dp), verticalArrangement = Arrangement.SpaceAround) { labels.forEach { Hint(it) } }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Sleep stages through the night" }) {
                if (length <= 0f) return@Canvas
                val rowH = size.height / 4
                for (i in 0..4) drawLine(grid, Offset(0f, rowH * i), Offset(size.width, rowH * i), strokeWidth = 1.dp.toPx())
                stages.forEach { s ->
                    val l = level(s.code) ?: return@forEach
                    val x0 = size.width * s.startS / length
                    val x1 = size.width * s.endS / length
                    val faded = n.wokeAt != null && s.startS >= wokeS
                    drawRect(
                        color(s.code).copy(alpha = if (faded) 0.3f else 1f),
                        Offset(x0, rowH * l + rowH * 0.15f), Size((x1 - x0).coerceAtLeast(1f), rowH * 0.7f),
                    )
                }
                if (n.wokeAt != null && wokeS < length) {
                    val x = size.width * wokeS / length
                    drawLine(
                        StageColors.Awake, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Woke is the dashed line; its time is in the line above the chart.
                Hint(n.bedStart.takeLast(5))
                Hint(n.bedEnd.takeLast(5))
            }
        }
    }
}

@Composable
private fun StagesCard(n: SleepPeriodEntity) {
    val total = n.totalSleepS ?: return
    SectionCard("Stages") {
        listOfNotNull(
            n.deepS?.let { Triple("Deep", it, StageColors.Deep) },
            n.lightS?.let { Triple("Light", it, StageColors.Light) },
            n.remS?.let { Triple("REM", it, StageColors.Rem) },
            n.awakeS?.let { Triple("Awake in bed", it, StageColors.Awake) },
        ).forEach { (label, s, c) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).padding(0.dp)) { Canvas(Modifier.size(10.dp)) { drawCircle(c) } }
                Text(label, Modifier.weight(1f))
                Text(SleepView.hm(s))
                // Share of time asleep; awake has no share of that.
                Hint(if (label == "Awake in bed") "" else "${(s * 100.0 / total).roundToInt()}%", Modifier.width(40.dp))
            }
        }
    }
}

@Composable
private fun HeartCard(n: SleepPeriodEntity, points: List<SleepRules.Point>) {
    SectionCard("Heart rate overnight") {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            n.lowestHr?.let { Figure("${it.roundToInt()}", "lowest bpm") }
            n.avgHr?.let { Figure("${it.roundToInt()}", "average bpm") }
        }
        val values = SleepView.slots(points)
        if (values.count { it != null } >= 2) {
            val low = n.lowestHr
            val lowLine = low?.let { l -> values.map { v -> if (v != null && v <= l + 0.5) v else null } }
            LineChart(
                listOfNotNull(
                    LineSeries(values, MaterialTheme.colorScheme.primary),
                    lowLine?.let { LineSeries(it, Color.Transparent, line = false, dots = LocalStatusColors.current.good) },
                ),
                "Heart rate through the night",
            )
            Hint("Every 5 minutes. The line breaks where nothing was sent; the green dot is the lowest.")
        }
    }
}

@Composable
private fun HrvCard(n: SleepPeriodEntity, points: List<SleepRules.Point>) {
    SectionCard("HRV overnight") {
        n.avgHrv?.let { Figure("${it.roundToInt()} ms", "average (RMSSD)") }
        val values = SleepView.slots(points)
        if (values.count { it != null } >= 2) {
            LineChart(listOf(LineSeries(values, MaterialTheme.colorScheme.secondary)), "HRV through the night")
        }
    }
}

@Composable
private fun NapCard(n: SleepPeriodEntity) {
    SectionCard("Nap") {
        Text(SleepView.asleep(n), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Hint(SleepView.times(n))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Hint("A nap counts on the day it ends.")
    }
}

private enum class SleepTab(val label: String) { DURATION("Duration"), BED("Bed & wake"), HEART("HR & HRV") }

/** Metrics → Sleep: duration by stage, bed and wake against the alarm and porch scan, and HR/HRV. */
@Composable
fun SleepCardView(card: SleepMetrics.Card, from: LocalDate, to: LocalDate) {
    var tab by rememberSaveable { mutableStateOf(SleepTab.DURATION) }
    SectionCard("Sleep") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepTab.entries.forEach { t -> FilterChip(t == tab, { tab = t }, label = { Text(t.label) }) }
        }
        val per = if (card.daily) "night" else "week, averaged"
        when (tab) {
            SleepTab.DURATION -> {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(card.averageAsleepS?.let { SleepView.hm(it) } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Hint("average asleep · ${card.nights} of ${card.days} nights", Modifier.padding(bottom = 4.dp))
                }
                StackedBarChart(
                    card.stages.map { it?.let(::StackBar) },
                    listOf(StageColors.Deep, StageColors.Light, StageColors.Rem, MaterialTheme.colorScheme.onSurfaceVariant),
                    "Hours asleep by stage, one bar per $per",
                    label = { "${it.roundToInt()}h" },
                    outlined = card.deload,
                )
                ChartDates(from.short(), to.short())
                Legend(listOf("Deep" to StageColors.Deep, "Light" to StageColors.Light, "REM" to StageColors.Rem))
                Hint("One bar per $per. Outlined bars are deload weeks; a night with no data is a gap.")
            }
            SleepTab.BED -> {
                BedChart(card.bed)
                ChartDates(from.short(), to.short())
                Legend(listOf("Bed → woke" to MaterialTheme.colorScheme.primary, "Alarm" to LocalStatusColors.current.warn, "Porch scan" to LocalStatusColors.current.good))
                Hint("The faded end of a bar is lying awake after waking, before getting up.")
            }
            SleepTab.HEART -> {
                Text("Lowest heart rate overnight", fontWeight = FontWeight.SemiBold)
                if (card.lowestHr.count { it != null } >= 1) {
                    LineChart(listOf(LineSeries(card.lowestHr, MaterialTheme.colorScheme.primary, dots = MaterialTheme.colorScheme.primary)), "Lowest heart rate by $per", height = 110.dp)
                } else Hint("No heart rate in this range.")
                Text("Average HRV (RMSSD)", fontWeight = FontWeight.SemiBold)
                if (card.hrv.count { it != null } >= 1) {
                    LineChart(listOf(LineSeries(card.hrv, MaterialTheme.colorScheme.secondary, dots = MaterialTheme.colorScheme.secondary)), "Average HRV by $per", height = 110.dp)
                } else Hint("No HRV in this range.")
                ChartDates(from.short(), to.short())
            }
        }
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (label, c) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(c) }
                Hint(label)
            }
        }
    }
}

/** One column per night (or week): bed at the top, woke, then up, with the alarm as a tick and the scan as a dot. */
@Composable
private fun BedChart(bars: List<SleepMetrics.Bed?>) {
    val known = bars.filterNotNull()
    val bar = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val alarm = LocalStatusColors.current.warn
    val scan = LocalStatusColors.current.good
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(170.dp).semantics { contentDescription = "Bedtime and wake time each night, with alarm and porch scan" }) {
        if (known.isEmpty()) return@Canvas
        val marks = known.flatMap { listOfNotNull(it.bed, it.woke, it.up, it.alarm, it.scan) }
        // Whole hours either side, so the labels land on round times.
        val lo = (marks.min() / 60) * 60
        val hi = (marks.max() / 60 + 1) * 60
        val gutter = 40.dp.toPx()
        val plotW = size.width - gutter
        val top = 6.dp.toPx()
        val plotH = size.height - top * 2
        fun y(m: Int) = top + plotH * (m - lo) / (hi - lo).toFloat()
        val step = if (hi - lo > 8 * 60) 240 else 120
        var g = lo
        while (g <= hi) {
            drawLine(grid, Offset(0f, y(g)), Offset(plotW, y(g)), strokeWidth = 1.dp.toPx())
            val t = measurer.measure(SleepMetrics.clock(g), TextStyle(fontSize = 10.sp, color = labelColor))
            drawText(t, topLeft = Offset(size.width - t.size.width, (y(g) - t.size.height / 2).coerceIn(0f, size.height - t.size.height)))
            g += step
        }
        val slot = plotW / bars.size
        val w = (slot * 0.6f).coerceAtMost(14.dp.toPx())
        bars.forEachIndexed { i, b ->
            if (b == null) return@forEachIndexed
            val cx = slot * i + slot / 2
            val woke = b.woke ?: b.up
            drawRoundRect(bar, Offset(cx - w / 2, y(b.bed)), Size(w, (y(woke) - y(b.bed)).coerceAtLeast(1f)), CornerRadius(3.dp.toPx()))
            if (b.up > woke) drawRoundRect(bar.copy(alpha = 0.3f), Offset(cx - w / 2, y(woke)), Size(w, y(b.up) - y(woke)), CornerRadius(3.dp.toPx()))
            b.alarm?.let { a -> drawLine(alarm, Offset(cx - w / 2 - 3.dp.toPx(), y(a)), Offset(cx + w / 2 + 3.dp.toPx(), y(a)), strokeWidth = 2.dp.toPx()) }
            b.scan?.let { s -> drawCircle(scan, 3.dp.toPx(), Offset(cx, y(s))) }
        }
    }
}
