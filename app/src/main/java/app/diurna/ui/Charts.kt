package app.diurna.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import kotlin.math.roundToInt

/** One line on a [LineChart]. Values are evenly spaced; a null breaks the line, so gaps in the data stay visible. */
data class LineSeries(
    val values: List<Double?>,
    val color: Color,
    val width: Dp = 2.5.dp,
    val dashed: Boolean = false,
    /** Draws each point as a dot in this colour. */
    val dots: Color? = null,
    val line: Boolean = true,
)

private val GutterRight = 34.dp
private val LabelStyle = TextStyle(fontSize = 10.sp)

/** A line chart with three labelled gridlines; [yRange] fixes the scale (e.g. 1–5 ratings), else it fits the data. */
@Composable
fun LineChart(
    series: List<LineSeries>,
    description: String,
    modifier: Modifier = Modifier,
    yRange: ClosedFloatingPointRange<Double>? = null,
    height: Dp = 140.dp,
    label: (Double) -> String = { it.roundToInt().toString() },
) {
    val all = series.flatMap { it.values }.filterNotNull()
    val (lo, hi) = yRange?.let { it.start to it.endInclusive } ?: fit(all)
    val grid = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val plotW = size.width - GutterRight.toPx()
        val top = 6.dp.toPx()
        val plotH = size.height - top * 2
        fun y(v: Double) = top + (plotH * (hi - v) / (hi - lo)).toFloat()
        gridlines(listOf(hi, (lo + hi) / 2, lo), ::y, plotW, grid, labelColor, measurer, label)
        series.forEach { s ->
            val n = s.values.size
            fun x(i: Int) = if (n <= 1) plotW / 2 else plotW * i / (n - 1)
            val stroke = Stroke(
                s.width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
            if (s.line) {
                val path = Path()
                var drawing = false
                s.values.forEachIndexed { i, v ->
                    if (v == null) { drawing = false; return@forEachIndexed }
                    val isolated = s.values.getOrNull(i - 1) == null && s.values.getOrNull(i + 1) == null
                    if (isolated && s.dots == null) drawCircle(s.color, s.width.toPx(), Offset(x(i), y(v)))
                    if (drawing) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
                    drawing = true
                }
                drawPath(path, s.color, style = stroke)
            }
            s.dots?.let { c -> s.values.forEachIndexed { i, v -> if (v != null) drawCircle(c, 2.5.dp.toPx(), Offset(x(i), y(v))) } }
        }
    }
}

/** Bars from zero, the last one highlighted (the current, unfinished period). */
@Composable
fun BarChart(
    values: List<Double>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    label: (Double) -> String = { it.roundToInt().toString() },
) {
    val hi = (values.maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0
    val base = MaterialTheme.colorScheme.primaryContainer
    val current = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val plotW = size.width - GutterRight.toPx()
        val top = 6.dp.toPx()
        val plotH = size.height - top
        fun y(v: Double) = top + (plotH * (hi - v) / hi).toFloat()
        gridlines(listOf(hi, hi / 2, 0.0), ::y, plotW, grid, labelColor, measurer, label)
        if (values.isEmpty()) return@Canvas
        val slot = plotW / values.size
        val gap = (slot * 0.2f).coerceAtMost(8.dp.toPx())
        values.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            drawRoundRect(
                if (i == values.lastIndex) current else base,
                topLeft = Offset(i * slot + gap / 2, y(v)),
                size = Size(slot - gap, y(0.0) - y(v)),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

/**
 * Stacked bars from zero. Each bar is one [StackBar]; a null bar is a gap.
 * Segments are drawn bottom-to-top in list order. [yMax] fixes the scale (100 for %);
 * otherwise it fits the tallest bar. [reference] draws a dashed horizontal (e.g. target kcal).
 */
data class StackBar(val segments: List<Double>)

@Composable
fun StackedBarChart(
    bars: List<StackBar?>,
    colors: List<Color>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    yMax: Double? = null,
    reference: Double? = null,
    label: (Double) -> String = { it.roundToInt().toString() },
) {
    val totals = bars.mapNotNull { it?.segments?.sum() }
    val hi = (yMax ?: (totals.maxOrNull() ?: 0.0).let { t ->
        val ref = reference?.takeIf { it > 0 }
        maxOf(t, ref ?: 0.0)
    }).takeIf { it > 0 } ?: 1.0
    val grid = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val refColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val plotW = size.width - GutterRight.toPx()
        val top = 6.dp.toPx()
        val plotH = size.height - top
        fun y(v: Double) = top + (plotH * (hi - v) / hi).toFloat()
        gridlines(listOf(hi, hi / 2, 0.0), ::y, plotW, grid, labelColor, measurer, label)
        if (bars.isEmpty()) return@Canvas
        val slot = plotW / bars.size
        val gap = (slot * 0.2f).coerceAtMost(8.dp.toPx())
        bars.forEachIndexed { i, bar ->
            if (bar == null) return@forEachIndexed
            var from = 0.0
            bar.segments.forEachIndexed { si, v ->
                if (v <= 0) return@forEachIndexed
                val to = from + v
                val color = colors.getOrElse(si) { colors.last() }
                drawRoundRect(
                    color,
                    topLeft = Offset(i * slot + gap / 2, y(to)),
                    size = Size(slot - gap, y(from) - y(to)),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                from = to
            }
        }
        reference?.takeIf { it > 0 }?.let { r ->
            val yy = y(r.coerceAtMost(hi))
            drawLine(
                refColor,
                Offset(0f, yy),
                Offset(plotW, yy),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
    }
}

/** A tiny trend line with no axes, for list rows. */
@Composable
fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier) {
    val known = values.filterNotNull()
    Canvas(modifier) {
        if (known.isEmpty()) return@Canvas
        val (lo, hi) = fit(known, pad = 0.15)
        val n = values.size
        fun x(i: Int) = if (n <= 1) size.width / 2 else size.width * i / (n - 1)
        fun y(v: Double) = (size.height * (hi - v) / (hi - lo)).toFloat()
        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            if (started) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
            started = true
        }
        if (known.size == 1) drawCircle(color, 2.dp.toPx(), Offset(x(values.indexOfFirst { it != null }), size.height / 2))
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** First and last date under a chart, lined up with its plot area. */
@Composable
fun ChartDates(first: String, last: String) {
    Row(Modifier.fillMaxWidth().padding(end = GutterRight), horizontalArrangement = Arrangement.SpaceBetween) {
        Hint(first)
        Hint(last)
    }
}

/** Scale that fits [values] with some headroom; a flat series gets a band around it. */
private fun fit(values: List<Double>, pad: Double = 0.1): Pair<Double, Double> {
    if (values.isEmpty()) return 0.0 to 1.0
    val lo = values.min()
    val hi = values.max()
    val span = (hi - lo).takeIf { it > 0 } ?: maxOf(1.0, hi * 0.02)
    return (lo - span * pad) to (hi + span * pad)
}

private fun DrawScope.gridlines(
    at: List<Double>, y: (Double) -> Float, plotW: Float, color: Color, labelColor: Color,
    measurer: TextMeasurer, label: (Double) -> String,
) {
    at.forEach { v ->
        drawLine(color, Offset(0f, y(v)), Offset(plotW, y(v)), strokeWidth = 1.dp.toPx())
        val text = measurer.measure(label(v), LabelStyle.copy(color = labelColor))
        drawText(text, topLeft = Offset(size.width - text.size.width, (y(v) - text.size.height / 2).coerceIn(0f, size.height - text.size.height)))
    }
}
