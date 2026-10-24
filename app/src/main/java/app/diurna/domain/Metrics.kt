package app.diurna.domain

import java.time.DayOfWeek
import java.time.LocalDate

/** Trends over a range of days: weight averages, ratings, and lifting sessions, PRs and volume. */
object Metrics {
    enum class Range(val label: String, val days: Long?) {
        W4("4w", 28), W12("12w", 84), Y1("1y", 365), ALL("All", null);

        /** First day of the range ending [today]; All starts at the first day with any data. */
        fun start(today: LocalDate, firstDate: LocalDate?): LocalDate =
            days?.let { today.minusDays(it - 1) } ?: minOf(firstDate ?: today, today)
    }

    /** The 7-day average needs this many weigh-ins in its window, so one reading never passes for a trend. */
    const val AVG_MIN_POINTS = 3
    private const val AVG_WINDOW_DAYS = 7L

    /**
     * Each day's average of the weigh-ins on it and the 6 days before; null with fewer than
     * [AVG_MIN_POINTS]. [weights] may reach back before [from] so the first days have a full window.
     */
    fun rollingAverage(weights: Map<LocalDate, Double>, from: LocalDate, to: LocalDate): List<Double?> =
        days(from, to).map { d ->
            val window = (0 until AVG_WINDOW_DAYS).mapNotNull { weights[d.minusDays(it)] }
            if (window.size >= AVG_MIN_POINTS) window.average() else null
        }

    /** Last known value minus the first; null unless two different points exist. */
    fun change(series: List<Double?>): Double? {
        val first = series.indexOfFirst { it != null }
        val last = series.indexOfLast { it != null }
        if (first < 0 || first == last) return null
        return series[last]!! - series[first]!!
    }

    data class DayRating(val date: LocalDate, val mood: Int?, val energy: Int?)
    data class RatingSummary(val mood: Double?, val energy: Double?, val rated: Int, val days: Int)

    /** Averages count rated days only; a day counts as rated when either scale is filled in. */
    fun ratingSummary(ratings: List<DayRating>): RatingSummary = RatingSummary(
        mood = ratings.mapNotNull { it.mood }.takeIf { it.isNotEmpty() }?.average(),
        energy = ratings.mapNotNull { it.energy }.takeIf { it.isNotEmpty() }?.average(),
        rated = ratings.count { it.mood != null || it.energy != null },
        days = ratings.size,
    )

    // ---- Lifting ----

    /** Epley tops out here: past 12 reps it overestimates, so those sets don't count toward e1RM. */
    const val E1RM_MAX_REPS = 12

    /** Estimated one-rep max, weight × (1 + reps/30). One rep is the weight itself. */
    fun e1rm(weight: Double?, reps: Int?): Double? {
        if (weight == null || weight <= 0 || reps == null || reps !in 1..E1RM_MAX_REPS) return null
        return if (reps == 1) weight else weight * (1 + reps / 30.0)
    }

    /** One logged set, with the session it belongs to. */
    data class SetEntry(
        val date: LocalDate,
        val exerciseId: Long,
        val name: String,
        val loggedId: Long,
        val weight: Double?,
        val reps: Int?,
    )

    /** One exercise on one day. */
    data class Session(
        val date: LocalDate,
        val exerciseId: Long,
        val name: String,
        val loggedId: Long,
        val sets: List<SetEntry>,
    ) {
        val bestE1rm: Double? = sets.mapNotNull { e1rm(it.weight, it.reps) }.maxOrNull()
        /** Heaviest weight lifted for at least one rep. */
        val topWeight: Double? = sets.filter { (it.reps ?: 0) >= 1 }.mapNotNull { it.weight }.filter { it > 0 }.maxOrNull()
        /** The set behind [topWeight], with the most reps at that weight. */
        val topSet: SetEntry? = topWeight?.let { w -> sets.filter { it.weight == w }.maxByOrNull { it.reps ?: 0 } }
        /** Σ weight × reps; sets missing either count 0. */
        val volume: Double = sets.sumOf { (it.weight ?: 0.0).coerceAtLeast(0.0) * (it.reps ?: 0).coerceAtLeast(0) }
    }

    /** Groups sets by session in the order given, leaving out sessions with no reps filled in (planned, not lifted). */
    fun sessions(sets: List<SetEntry>): List<Session> =
        sets.groupBy { it.loggedId }.values
            .filter { s -> s.any { it.reps != null } }
            .map { s -> s.first().let { Session(it.date, it.exerciseId, it.name, it.loggedId, s) } }
            .sortedBy { it.date }

    data class Pr(val session: Session, val e1rm: Boolean, val weight: Boolean)

    /**
     * Sessions that beat every earlier session of the same exercise on best e1RM or top weight.
     * Judged against all history, so pass every session, not just a range. A first session sets the
     * baseline and isn't a PR; a tie isn't one either.
     */
    fun prs(sessions: List<Session>): List<Pr> {
        val bestE1rm = mutableMapOf<Long, Double>()
        val bestWeight = mutableMapOf<Long, Double>()
        val seen = mutableSetOf<Long>()
        val out = mutableListOf<Pr>()
        for (s in sessions.sortedBy { it.date }) {
            val first = seen.add(s.exerciseId)
            val prevE = bestE1rm[s.exerciseId]
            val prevW = bestWeight[s.exerciseId]
            val e = !first && s.bestE1rm != null && (prevE == null || s.bestE1rm > prevE)
            val w = !first && s.topWeight != null && (prevW == null || s.topWeight > prevW)
            if (e || w) out += Pr(s, e, w)
            s.bestE1rm?.let { if (prevE == null || it > prevE) bestE1rm[s.exerciseId] = it }
            s.topWeight?.let { if (prevW == null || it > prevW) bestWeight[s.exerciseId] = it }
        }
        return out
    }

    fun weekOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    data class WeekVolume(val monday: LocalDate, val volume: Double)

    /**
     * Total volume per Monday-to-Sunday week, every week touching [from]..[to], empty weeks included.
     * Each bar is a whole week, even where the range starts mid-week, so the first bar isn't cut short.
     */
    fun weeklyVolume(sessions: List<Session>, from: LocalDate, to: LocalDate): List<WeekVolume> {
        val totals = sessions.filter { it.date in weekOf(from)..to }.groupBy { weekOf(it.date) }.mapValues { (_, s) -> s.sumOf { it.volume } }
        return generateSequence(weekOf(from)) { it.plusWeeks(1) }.takeWhile { it <= to }
            .map { WeekVolume(it, totals[it] ?: 0.0) }.toList()
    }

    data class Usage(val exerciseId: Long, val name: String, val sessions: Int, val last: LocalDate)

    /** Exercises by sessions in the range, ties going to the most recently done. */
    fun mostLogged(sessions: List<Session>, from: LocalDate, to: LocalDate, n: Int = 5): List<Usage> =
        sessions.filter { it.date in from..to }.groupBy { it.exerciseId }
            .map { (id, s) -> Usage(id, s.last().name, s.size, s.maxOf { it.date }) }
            .sortedWith(compareByDescending<Usage> { it.sessions }.thenByDescending { it.last })
            .take(n)

    fun days(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()
}
