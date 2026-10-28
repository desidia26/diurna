package app.diurna.ui

import app.diurna.AppContainer
import app.diurna.data.MetricsRepository
import app.diurna.domain.Cardio
import app.diurna.domain.Metrics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

data class WeightCard(
    /** One entry per day of the range; null where nothing was logged. */
    val points: List<Double?>,
    val average: List<Double?>,
    val latestAverage: Double?,
    val change: Double?,
    val logged: Int,
    val days: Int,
    val low: Pair<Double, LocalDate>?,
    val high: Pair<Double, LocalDate>?,
    val latest: Double?,
)

data class MoodCard(
    val mood: List<Double?>,
    val energy: List<Double?>,
    val summary: Metrics.RatingSummary,
    /** Past 4 weeks, daily ratings scribble; the lines become 7-day averages instead. */
    val smoothed: Boolean,
)

data class MacroCard(
    /** One stack per day (4w) or per week (longer); null = gap (no food). Absolute kcal. */
    val stacks: List<Metrics.MacroStack?>,
    /** Same shape as [stacks], each bar scaled to 100; null when total is 0. */
    val percentStacks: List<Metrics.MacroStack?>,
    val mode: Metrics.MacroChartMode,
    /** Mean daily (or weekly-average) total macro kcal over non-gap bars. */
    val averageKcal: Double?,
    /** Mean split used for the headline percentages. */
    val average: Metrics.MacroStack?,
    val daysWithFood: Int,
    val periods: Int,
    /** Target kcal for the dashed line in kcal mode; null when no profile/weight. */
    val targetKcal: Int?,
    /** True when bars are days; false when they're week averages. */
    val daily: Boolean,
)

data class CarbDetailCard(
    /** One stack per day (4w) or week average; null = gap (no carb detail that period). */
    val stacks: List<Metrics.CarbStack?>,
    val average: Metrics.CarbStack?,
    val coverage: Metrics.DetailCoverage,
    val daily: Boolean,
    val splitFiber: Boolean,
    val showAdded: Boolean,
)

data class FatDetailCard(
    val stacks: List<Metrics.FatStack?>,
    val average: Metrics.FatStack?,
    val coverage: Metrics.DetailCoverage,
    val daily: Boolean,
)

data class LipidDetailCard(
    val summary: Metrics.LipidSummary,
)

data class AminoDetailCard(
    val summary: Metrics.AminoSummary,
)

data class TrainingCard(
    val weeks: List<Metrics.WeekVolume>,
    val thisWeek: Double,
    /** This week against the average of the earlier weeks in the range, as a fraction (0.08 = +8%). */
    val vsEarlier: Double?,
    val workouts: Int,
    val prs: Int,
)

data class CardioCard(
    /** Weekly totals in [unit], oldest first: distance when anything had one, else minutes. */
    val weeks: List<Cardio.WeekTotal>,
    val weekly: List<Double>,
    /** "m", "km", "mi" or "min". */
    val unit: String,
    val thisWeek: Double,
    val sessions: Int,
    val prs: Int,
    /** Every cardio exercise done in the range, most sessions first. */
    val rows: List<CardioRow>,
)

data class CardioRow(val exerciseId: Long, val name: String, val last: String, val spark: List<Double?>)

/** What a cardio exercise's detail can chart; which ones it offers depends on its [Cardio.Style]. */
enum class CardioMetric { DISTANCE, PACE, POWER, TIME, KCAL }

/** "Split" for rowing, "Speed" for cycling, "Pace" for running. */
fun CardioMetric.label(style: Cardio.Style): String = when (this) {
    CardioMetric.DISTANCE -> "Distance"
    CardioMetric.PACE -> when {
        style == Cardio.Style.ROW -> "Split"
        style.pace.isSpeed -> "Speed"
        else -> "Pace"
    }
    CardioMetric.POWER -> "Power"
    CardioMetric.TIME -> "Time"
    CardioMetric.KCAL -> "Calories"
}

/** Lower is better only for a pace ("2:10/500m", "8:23/mi"); a speed is the other way up. */
fun CardioMetric.lowerIsBetter(style: Cardio.Style): Boolean = this == CardioMetric.PACE && !style.pace.isSpeed

data class CardioSessionRow(val date: LocalDate, val text: String, val format: String?, val value: String, val pr: Boolean)

data class CardioRecord(val label: String, val value: String, val date: LocalDate)

data class CardioDetail(
    val exerciseId: Long,
    val name: String,
    val style: Cardio.Style,
    /** The metrics this exercise offers, in chip order. */
    val metrics: List<CardioMetric>,
    val metric: CardioMetric,
    /** One value per session in the range, oldest first, in the shown unit (mi, s/mi, mph, W, min, kcal). */
    val values: List<Double?>,
    val first: LocalDate?,
    val last: LocalDate?,
    val current: Double?,
    val change: Double?,
    /** All-time, not just the range. */
    val records: List<CardioRecord>,
    /** Sessions in the range, newest first. */
    val sessions: List<CardioSessionRow>,
)

data class LiftRow(val exerciseId: Long, val name: String, val best: String?, val e1rm: Double?, val spark: List<Double?>, val change: Double?)

data class MetricsUi(
    val range: Metrics.Range,
    val from: LocalDate,
    val to: LocalDate,
    /** Daily dots get too dense past 12 weeks, so longer ranges show the average alone. */
    val showDots: Boolean,
    val weight: WeightCard,
    val mood: MoodCard,
    /** Null until any food has ever been estimated, so people who don't log food never see it. */
    val macros: MacroCard?,
    /** Carb/fat/lipid breakdowns; null when Detailed nutrition is off or no sub-macro data in range. */
    val carbs: CarbDetailCard?,
    val fats: FatDetailCard?,
    val lipids: LipidDetailCard?,
    val aminos: AminoDetailCard?,
    val training: TrainingCard,
    /** Null until any cardio has ever been logged, so people who only lift never see the card. */
    val cardio: CardioCard?,
    /** Every exercise done in the range, most logged first; the card shows the top few. */
    val lifts: List<LiftRow>,
)

enum class LiftMetric(val label: String) { E1RM("e1RM"), VOLUME("Volume") }

data class SessionRow(val date: LocalDate, val sets: String, val e1rm: Double?, val volume: Double, val pr: Boolean)

data class ExerciseDetail(
    val exerciseId: Long,
    val name: String,
    val metric: LiftMetric,
    /** One value per session in the range, oldest first. */
    val values: List<Double?>,
    val first: LocalDate?,
    val last: LocalDate?,
    val current: Double?,
    val change: Double?,
    // Records are all-time, not just the range.
    val bestE1rm: Pair<Double, LocalDate>?,
    val heaviest: Pair<String, LocalDate>?,
    val bestVolume: Pair<Double, LocalDate>?,
    /** Sessions in the range, newest first. */
    val sessions: List<SessionRow>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MetricsViewModel(private val c: AppContainer) : ScreenViewModel() {
    private val today = minuteTicker.map { it.toLocalDate() }.distinctUntilChanged()

    private val snapshot = combine(c.settings.metricsRange, today, ::Pair)
        .flatMapLatest { (range, day) -> c.metrics.observe(range, day).map { range to it } }

    private val targets = today.flatMapLatest { c.nutrition.observeTargets(it) }

    val ui: StateFlow<MetricsUi?> = combine(
        snapshot, targets, c.settings.macroChartMode, c.settings.detailedNutrition,
    ) { snap, t, mode, detailed ->
        val (range, s) = snap
        build(range, s, t?.kcal, mode, detailed)
    }.state(null)

    private val openExercise = MutableStateFlow<Long?>(null)
    private val metric = MutableStateFlow(LiftMetric.E1RM)

    val detail: StateFlow<ExerciseDetail?> = combine(snapshot, openExercise, metric) { (_, s), id, m ->
        id?.let { detail(it, m, s) }
    }.state(null)

    private val openCardio = MutableStateFlow<Long?>(null)
    /** Null: the exercise's first metric (distance, or time for a time-only one). */
    private val cardioMetric = MutableStateFlow<CardioMetric?>(null)

    val cardioDetail: StateFlow<CardioDetail?> = combine(snapshot, openCardio, cardioMetric) { (_, s), id, m ->
        id?.let { cardioDetail(it, m, s) }
    }.state(null)

    fun openCardio(id: Long) { cardioMetric.value = null; openCardio.value = id }
    fun setCardioMetric(m: CardioMetric) { cardioMetric.value = m }

    fun setRange(range: Metrics.Range) = io { c.settings.setMetricsRange(range) }
    fun setMacroChartMode(mode: Metrics.MacroChartMode) = io { c.settings.setMacroChartMode(mode) }
    fun openExercise(id: Long) { metric.value = LiftMetric.E1RM; openExercise.value = id }
    fun closeExercise() { openExercise.value = null; openCardio.value = null }
    fun setMetric(m: LiftMetric) { metric.value = m }

    private fun build(
        range: Metrics.Range,
        s: MetricsRepository.Snapshot,
        targetKcal: Int?,
        mode: Metrics.MacroChartMode,
        detailedNutrition: Boolean,
    ): MetricsUi {
        val dates = Metrics.days(s.from, s.to)
        val byDate = s.days.associateBy { LocalDate.parse(it.date) }
        val inRange = dates.map { byDate[it] }

        val weights = s.days.mapNotNull { d -> d.bodyWeight?.let { LocalDate.parse(d.date) to it } }.toMap()
        val points = dates.map { weights[it] }
        val average = Metrics.rollingAverage(weights, s.from, s.to)
        val logged = dates.filter { it in weights }
        val weight = WeightCard(
            points = points,
            average = average,
            latestAverage = average.lastOrNull { it != null },
            change = Metrics.change(average),
            logged = logged.size,
            days = dates.size,
            low = logged.minByOrNull { weights.getValue(it) }?.let { weights.getValue(it) to it },
            high = logged.maxByOrNull { weights.getValue(it) }?.let { weights.getValue(it) to it },
            latest = logged.lastOrNull()?.let { weights[it] },
        )

        val smoothed = range != Metrics.Range.W4
        fun ratings(pick: (app.diurna.data.DayEntity) -> Int?): List<Double?> =
            if (smoothed) Metrics.rollingAverage(s.days.mapNotNull { d -> pick(d)?.let { LocalDate.parse(d.date) to it.toDouble() } }.toMap(), s.from, s.to)
            else inRange.map { it?.let(pick)?.toDouble() }
        val mood = MoodCard(
            mood = ratings { it.mood },
            energy = ratings { it.energy },
            summary = Metrics.ratingSummary(dates.map { Metrics.DayRating(it, byDate[it]?.mood, byDate[it]?.energy) }),
            smoothed = smoothed,
        )

        val sessions = s.sessions.filter { it.date in s.from..s.to }
        val prs = Metrics.prs(s.sessions).filter { it.session.date in s.from..s.to }
        val weeks = Metrics.weeklyVolume(s.sessions, s.from, s.to)
        val earlier = weeks.dropLast(1).filter { it.volume > 0 }
        val thisWeek = weeks.lastOrNull()?.volume ?: 0.0
        val training = TrainingCard(
            weeks = weeks,
            thisWeek = thisWeek,
            vsEarlier = earlier.takeIf { it.isNotEmpty() }?.map { it.volume }?.average()?.let { thisWeek / it - 1 },
            workouts = sessions.map { it.date }.distinct().size,
            prs = prs.size,
        )

        val lifts = Metrics.mostLogged(s.sessions, s.from, s.to, n = Int.MAX_VALUE).map { u ->
            val own = sessions.filter { it.exerciseId == u.exerciseId }
            val spark = own.map { it.bestE1rm }
            LiftRow(
                exerciseId = u.exerciseId,
                name = u.name,
                best = own.lastOrNull()?.topSet?.let(::setText),
                e1rm = spark.lastOrNull { it != null },
                spark = spark,
                change = Metrics.change(spark),
            )
        }
        val macros = macroCard(range, s, targetKcal, mode).takeIf { s.hasEstimatedFood }
        val showDetail = detailedNutrition && s.macroGrams.values.any { it.hasAnyDetail }
        val carbs = if (showDetail) carbDetailCard(range, s) else null
        val fats = if (showDetail) fatDetailCard(range, s) else null
        val lipids = if (showDetail) lipidDetailCard(s) else null
        val aminos = if (showDetail) aminoDetailCard(s) else null

        return MetricsUi(
            range, s.from, s.to, (range.days ?: Long.MAX_VALUE) <= 84,
            weight, mood, macros, carbs, fats, lipids, aminos, training,
            cardioCard(s).takeIf { s.cardio.isNotEmpty() }, lifts,
        )
    }

    private fun macroCard(
        range: Metrics.Range,
        s: MetricsRepository.Snapshot,
        targetKcal: Int?,
        mode: Metrics.MacroChartMode,
    ): MacroCard {
        val daily = range == Metrics.Range.W4
        val stacks = if (daily) Metrics.dailyMacroStacks(s.macros, s.from, s.to)
        else Metrics.weeklyMacroAverages(s.macros, s.from, s.to)
        return MacroCard(
            stacks = stacks,
            percentStacks = stacks.map(Metrics::macroPercent),
            mode = mode,
            averageKcal = Metrics.averageMacroTotal(stacks),
            average = Metrics.averageMacroStack(stacks),
            daysWithFood = s.macros.size,
            periods = stacks.size,
            targetKcal = targetKcal,
            daily = daily,
        )
    }

    private fun carbDetailCard(range: Metrics.Range, s: MetricsRepository.Snapshot): CarbDetailCard? {
        val coverage = Metrics.carbCoverage(s.macroGrams)
        if (coverage.withDetail == 0) return null
        val daily = range == Metrics.Range.W4
        val stacks = if (daily) Metrics.dailyCarbStacks(s.macroGrams, s.from, s.to)
        else Metrics.weeklyCarbAverages(s.macroGrams, s.from, s.to)
        val known = stacks.filterNotNull()
        return CarbDetailCard(
            stacks = stacks,
            average = Metrics.averageCarbStack(known),
            coverage = coverage,
            daily = daily,
            splitFiber = known.any { it.splitFiber },
            showAdded = known.any { it.showAdded },
        )
    }

    private fun fatDetailCard(range: Metrics.Range, s: MetricsRepository.Snapshot): FatDetailCard? {
        val coverage = Metrics.fatCoverage(s.macroGrams)
        if (coverage.withDetail == 0) return null
        val daily = range == Metrics.Range.W4
        val stacks = if (daily) Metrics.dailyFatStacks(s.macroGrams, s.from, s.to)
        else Metrics.weeklyFatAverages(s.macroGrams, s.from, s.to)
        val known = stacks.filterNotNull()
        return FatDetailCard(
            stacks = stacks,
            average = Metrics.averageFatStack(known),
            coverage = coverage,
            daily = daily,
        )
    }

    private fun lipidDetailCard(s: MetricsRepository.Snapshot): LipidDetailCard? =
        Metrics.lipidSummary(s.macroGrams)?.let(::LipidDetailCard)

    private fun aminoDetailCard(s: MetricsRepository.Snapshot): AminoDetailCard? =
        Metrics.aminoSummary(s.macroGrams)?.let(::AminoDetailCard)

    private fun cardioCard(s: MetricsRepository.Snapshot): CardioCard {
        val ranged = s.cardio.filter { it.date in s.from..s.to }
        val weeks = Cardio.weeklyTotals(s.cardio, s.from, s.to)
        // Distance in the first non-metre style's unit (so runners see miles), metres for rowing alone.
        val withDistance = ranged.filter { it.style.hasDistance && (it.distanceM ?: 0) > 0 }
        val unitStyle = withDistance.firstOrNull { it.style != Cardio.Style.ROW }?.style ?: withDistance.firstOrNull()?.style
        val (unit, weekly) = if (unitStyle != null) {
            unitStyle.unitLabel to weeks.map { it.metres / unitStyle.unitM!! }
        } else {
            "min" to weeks.map { it.seconds / 60 }
        }
        val rows = ranged.groupBy { it.exerciseId }.values.sortedByDescending { it.size }.map { own ->
            val style = own.last().style
            CardioRow(
                exerciseId = own.first().exerciseId,
                name = own.last().name,
                last = own.last().let { Cardio.summary(style, it.distanceM, it.durationS, it.splitS, kcal = it.kcal) },
                spark = own.map { if (style.hasDistance) it.distanceM?.toDouble() else it.durationS },
            )
        }
        return CardioCard(
            weeks = weeks,
            weekly = weekly,
            unit = unit,
            thisWeek = weekly.lastOrNull() ?: 0.0,
            sessions = ranged.size,
            prs = Cardio.prs(s.cardio).count { it.session.date in s.from..s.to },
            rows = rows,
        )
    }

    private fun cardioDetail(id: Long, chosen: CardioMetric?, s: MetricsRepository.Snapshot): CardioDetail? {
        val all = s.cardio.filter { it.exerciseId == id }
        if (all.isEmpty()) return null
        val style = all.last().style
        val metrics = cardioMetrics(style, all)
        val metric = chosen?.takeIf { it in metrics } ?: metrics.first()
        val prs = Cardio.prs(s.cardio).filter { it.session.exerciseId == id }.associateBy { it.session.loggedId }
        val ranged = all.filter { it.date in s.from..s.to }
        val values = ranged.map { cardioShown(metric, style, it) }
        val records = listOfNotNull(
            all.filter { it.distanceM != null && style.hasDistance }.maxByOrNull { it.distanceM!! }
                ?.let { CardioRecord("Longest", Cardio.distanceText(it.distanceM!!, style), it.date) },
            all.filter { it.splitS != null && style.hasDistance }.minByOrNull { it.splitS!! }
                ?.let { CardioRecord("Best avg ${CardioMetric.PACE.label(style).lowercase()}", style.pace.text(it.splitS!!)!!, it.date) },
            all.filter { it.watts != null }.maxByOrNull { it.watts!! }?.let { CardioRecord("Best avg power", "${it.watts} W", it.date) },
            all.filter { it.durationS != null && !style.hasDistance }.maxByOrNull { it.durationS!! }
                ?.let { CardioRecord("Longest", Cardio.durationText(it.durationS!!), it.date) },
            all.filter { it.kcal != null && !style.hasDistance }.maxByOrNull { it.kcal!! }
                ?.let { CardioRecord("Most calories", "${it.kcal} kcal", it.date) },
        )
        return CardioDetail(
            exerciseId = id,
            name = all.last().name,
            style = style,
            metrics = metrics,
            metric = metric,
            values = values,
            first = ranged.firstOrNull()?.date,
            last = ranged.lastOrNull()?.date,
            current = values.lastOrNull { it != null },
            change = Metrics.change(values),
            records = records,
            sessions = ranged.reversed().map { x ->
                val pr = prs[x.loggedId]
                val starred = when (metric) {
                    CardioMetric.DISTANCE -> pr?.distance
                    CardioMetric.PACE -> pr?.split
                    CardioMetric.POWER -> pr?.watts
                    CardioMetric.KCAL -> pr?.kcal
                    // Longer isn't a PR within the same length; time-only sessions star for calories.
                    CardioMetric.TIME -> pr?.kcal
                } == true
                CardioSessionRow(
                    x.date,
                    Cardio.summary(style, x.distanceM, x.durationS, x.splitS, x.watts, x.kcal),
                    x.format?.label,
                    cardioShown(metric, style, x)?.let { cardioValue(metric, style, it) } ?: "—",
                    starred,
                )
            },
        )
    }

    private fun detail(id: Long, metric: LiftMetric, s: MetricsRepository.Snapshot): ExerciseDetail? {
        val all = s.sessions.filter { it.exerciseId == id }
        if (all.isEmpty()) return null
        val prDates = Metrics.prs(s.sessions).filter { it.session.exerciseId == id }.map { it.session.loggedId }.toSet()
        val ranged = all.filter { it.date in s.from..s.to }
        val values = ranged.map { if (metric == LiftMetric.E1RM) it.bestE1rm else it.volume.takeIf { v -> v > 0 } }
        return ExerciseDetail(
            exerciseId = id,
            name = all.last().name,
            metric = metric,
            values = values,
            first = ranged.firstOrNull()?.date,
            last = ranged.lastOrNull()?.date,
            current = values.lastOrNull { it != null },
            change = Metrics.change(values),
            bestE1rm = all.filter { it.bestE1rm != null }.maxByOrNull { it.bestE1rm!! }?.let { it.bestE1rm!! to it.date },
            heaviest = all.filter { it.topSet != null }.maxByOrNull { it.topWeight!! }?.let { setText(it.topSet!!) to it.date },
            bestVolume = all.filter { it.volume > 0 }.maxByOrNull { it.volume }?.let { it.volume to it.date },
            sessions = ranged.reversed().map { x ->
                SessionRow(x.date, x.sets.filter { it.reps != null }.joinToString(" · ", transform = ::setText), x.bestE1rm, x.volume, x.loggedId in prDates)
            },
        )
    }
}

/**
 * Distance and pace (or speed) for a distance style, power if it has it or any was logged; time
 * and calories for a time-only one.
 */
fun cardioMetrics(style: Cardio.Style, sessions: List<Cardio.Session>): List<CardioMetric> = buildList {
    if (style.hasDistance) { add(CardioMetric.DISTANCE); add(CardioMetric.PACE) }
    if (style.watts || sessions.any { it.watts != null }) add(CardioMetric.POWER)
    if (!style.hasDistance) { add(CardioMetric.TIME); add(CardioMetric.KCAL) }
}

/** A session's value in the unit it's shown in: mi or m, s/mi or mph, W, minutes, kcal. */
fun cardioShown(metric: CardioMetric, style: Cardio.Style, x: Cardio.Session): Double? = when (metric) {
    CardioMetric.DISTANCE -> x.distanceM?.let { m -> style.unitM?.let { m / it } }
    CardioMetric.PACE -> x.splitS?.let { style.pace.fromSplit(it) }
    CardioMetric.POWER -> x.watts?.toDouble()
    CardioMetric.TIME -> x.durationS?.let { it / 60 }
    CardioMetric.KCAL -> x.kcal?.toDouble()
}

/** A [cardioShown] value as text: "3,260 m", "3.1 mi", "2:10/500m", "16.2 mph", "158 W", "30 min", "250 kcal". */
fun cardioValue(metric: CardioMetric, style: Cardio.Style, v: Double): String = when (metric) {
    CardioMetric.DISTANCE -> Cardio.distanceText(((style.unitM ?: 1.0) * v).roundToInt(), style)
    CardioMetric.PACE -> if (style.pace.isSpeed) String.format(Locale.US, "%.1f %s", v, style.pace.label) else "${Cardio.clock(v)}${style.pace.label}"
    CardioMetric.POWER -> "${v.roundToInt()} W"
    CardioMetric.TIME -> Cardio.durationText(v * 60)
    CardioMetric.KCAL -> "${v.roundToInt()} kcal"
}

private fun setText(set: Metrics.SetEntry): String = "${set.weight?.fmt() ?: "—"} × ${set.reps ?: "—"}"
