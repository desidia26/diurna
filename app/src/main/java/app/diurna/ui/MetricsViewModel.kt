package app.diurna.ui

import app.diurna.AppContainer
import app.diurna.data.MetricsRepository
import app.diurna.domain.Metrics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate

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

data class TrainingCard(
    val weeks: List<Metrics.WeekVolume>,
    val thisWeek: Double,
    /** This week against the average of the earlier weeks in the range, as a fraction (0.08 = +8%). */
    val vsEarlier: Double?,
    val workouts: Int,
    val prs: Int,
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
    val training: TrainingCard,
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

    val ui: StateFlow<MetricsUi?> = snapshot.map { (range, s) -> build(range, s) }.state(null)

    private val openExercise = MutableStateFlow<Long?>(null)
    private val metric = MutableStateFlow(LiftMetric.E1RM)

    val detail: StateFlow<ExerciseDetail?> = combine(snapshot, openExercise, metric) { (_, s), id, m ->
        id?.let { detail(it, m, s) }
    }.state(null)

    fun setRange(range: Metrics.Range) = io { c.settings.setMetricsRange(range) }
    fun openExercise(id: Long) { metric.value = LiftMetric.E1RM; openExercise.value = id }
    fun closeExercise() { openExercise.value = null }
    fun setMetric(m: LiftMetric) { metric.value = m }

    private fun build(range: Metrics.Range, s: MetricsRepository.Snapshot): MetricsUi {
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
        return MetricsUi(range, s.from, s.to, (range.days ?: Long.MAX_VALUE) <= 84, weight, mood, training, lifts)
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

private fun setText(set: Metrics.SetEntry): String = "${set.weight?.fmt() ?: "—"} × ${set.reps ?: "—"}"
