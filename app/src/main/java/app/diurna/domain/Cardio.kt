package app.diurna.domain

import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Cardio pieces (a run, a ride, a row, half an hour on the elliptical): distance, time, pace, power
 * and calories instead of weight × reps, every one optional. Pure rules for the Day screen, Metrics
 * and the lock screen. Cardio never counts toward lifting volume or e1RM; see [Metrics.sessions].
 *
 * Pace is stored one way for every activity, seconds per 500 m ([SPLIT_M]); each exercise's
 * [Style] decides how it's typed and shown (per 500 m, per km or mile, or as a speed).
 */
object Cardio {
    /** Stored paces are seconds per 500 m, whatever the [Style] shows. */
    const val SPLIT_M = 500
    const val MILE_M = 1609.344

    /** How an exercise's pieces are typed and shown. All fields stay optional within a style. */
    enum class Style(
        val key: String,
        val label: String,
        /** Metres per distance unit; null for a time-only exercise, which doesn't ask for distance. */
        val unitM: Double?,
        val unitLabel: String,
        val pace: Pace,
        val watts: Boolean,
    ) {
        ROW("500m", "Rowing (/500m)", 1.0, "m", Pace.PER_500M, watts = true),
        RUN_KM("km", "Run or walk (/km)", 1000.0, "km", Pace.PER_KM, watts = false),
        RUN_MI("mi", "Run or walk (/mi)", MILE_M, "mi", Pace.PER_MILE, watts = false),
        RIDE_KM("kmh", "Cycling (km/h)", 1000.0, "km", Pace.KMH, watts = true),
        RIDE_MI("mph", "Cycling (mph)", MILE_M, "mi", Pace.MPH, watts = true),
        TIME("time", "Time and calories", null, "", Pace.NONE, watts = false);

        val hasDistance: Boolean get() = unitM != null

        companion object {
            /** Unknown or missing keys read as time-only, which asks for the least. */
            fun of(key: String?): Style = entries.firstOrNull { it.key == key } ?: TIME
        }
    }

    enum class Pace(
        /** Metres a shown pace is per, or the metres in a speed's unit; null for none. */
        val perM: Double?,
        val isSpeed: Boolean,
        val label: String,
    ) {
        PER_500M(500.0, false, "/500m"),
        PER_KM(1000.0, false, "/km"),
        PER_MILE(MILE_M, false, "/mi"),
        KMH(1000.0, true, "km/h"),
        MPH(MILE_M, true, "mph"),
        NONE(null, false, ""),
        ;

        /** A stored split (s/500 m) as this pace's number: seconds per its distance, or a speed. */
        fun fromSplit(splitS: Double): Double? {
            val m = perM ?: return null
            if (splitS <= 0) return null
            return if (isSpeed) 3600.0 / (splitS * m / SPLIT_M) else splitS * m / SPLIT_M
        }

        /** The reverse of [fromSplit]. */
        fun toSplit(value: Double): Double? {
            val m = perM ?: return null
            if (value <= 0) return null
            return if (isSpeed) 3600.0 / value * SPLIT_M / m else value * SPLIT_M / m
        }

        /** "2:10/500m", "5:12/km", "28.5 km/h". */
        fun text(splitS: Double): String? {
            val v = fromSplit(splitS) ?: return null
            return if (isSpeed) String.format(Locale.US, "%.1f %s", v, label) else "${clock(v)}$label"
        }
    }

    // Bounds for typed or AI-read values; anything outside is a misread, not a workout.
    const val MAX_DISTANCE_M = 1_000_000
    const val MAX_DURATION_S = 24 * 3600.0
    const val MAX_SPLIT_S = 3600.0
    const val MAX_WATTS = 3000
    const val MAX_KCAL = 20_000
    const val MAX_HR = 250

    /** Seconds per 500 m from a piece's time and distance; null without both. */
    fun split(durationS: Double?, distanceM: Int?): Double? {
        if (durationS == null || distanceM == null || durationS <= 0 || distanceM <= 0) return null
        return durationS / distanceM * SPLIT_M
    }

    /** The machine's split when it showed one, else worked out from time and distance. */
    fun effectiveSplit(splitS: Double?, durationS: Double?, distanceM: Int?): Double? =
        splitS?.takeIf { it > 0 } ?: split(durationS, distanceM)

    /** 907 → "15:07", 3723 → "1:02:03". Whole seconds; tenths are noise at a glance. */
    fun clock(seconds: Double): String {
        val total = seconds.roundToLong().coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** "3,260 m". */
    fun distanceText(m: Int): String = String.format(Locale.US, "%,d m", m)

    /** In the style's unit: "3,260 m", "5.0 km", "3.1 mi"; metres for a time-only style. */
    fun distanceText(m: Int, style: Style): String {
        val unit = style.unitM
        return if (unit == null || unit == 1.0) distanceText(m) else String.format(Locale.US, "%.1f %s", m / unit, style.unitLabel)
    }

    /** Whole minutes read as "30 min"; anything else as a clock, "15:07". */
    fun durationText(seconds: Double): String {
        val whole = seconds.roundToLong()
        return if (whole >= 60 && whole % 60 == 0L && whole < 3600) "${whole / 60} min" else clock(seconds)
    }

    /**
     * Reads "15:07", "1:02:03", "2:10.4" or plain seconds ("907"); null for anything else. Fields
     * past the first must be under 60, as on a clock.
     */
    fun parseClock(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val parts = t.split(':')
        if (parts.size > 3 || parts.any { it.isEmpty() }) return null
        val nums = parts.mapIndexed { i, p ->
            val isLast = i == parts.lastIndex
            val v = if (isLast) p.toDoubleOrNull() else p.toIntOrNull()?.toDouble()
            if (v == null || v < 0 || !p.all { it.isDigit() || (isLast && it == '.') }) return null
            if (i > 0 && v >= 60) return null
            v
        }
        return nums.fold(0.0) { acc, v -> acc * 60 + v }
    }

    /** One logged piece, with the session it belongs to. */
    data class Piece(
        val date: LocalDate,
        val exerciseId: Long,
        val name: String,
        val loggedId: Long,
        val distanceM: Int? = null,
        val durationS: Double? = null,
        val splitS: Double? = null,
        val bestSplitS: Double? = null,
        val watts: Int? = null,
        val kcal: Int? = null,
        val avgHr: Int? = null,
        val style: Style = Style.TIME,
    ) {
        val done: Boolean get() = distanceM != null || durationS != null
        val split: Double? get() = effectiveSplit(splitS, durationS, distanceM)
    }

    /**
     * A workout shape to compare like with like: a timed piece goes by its minutes ("15 min"),
     * rounded to the nearest minute so 15:07 and 14:58 match; a piece with only a distance goes by
     * metres.
     */
    data class Format(val key: String, val label: String) {
        companion object {
            fun of(durationS: Double?, distanceM: Int?, style: Style = Style.ROW): Format? = when {
                durationS != null && durationS >= 60 -> (durationS / 60).roundToInt().let { Format("t$it", "$it min") }
                distanceM != null && distanceM > 0 -> Format("d$distanceM", distanceText(distanceM, style))
                else -> null
            }
        }
    }

    /** One cardio exercise on one day; several pieces (intervals) add up. */
    data class Session(
        val date: LocalDate,
        val exerciseId: Long,
        val name: String,
        val loggedId: Long,
        val pieces: List<Piece>,
    ) {
        private val done = pieces.filter { it.done }
        val style: Style = pieces.first().style
        val distanceM: Int? = done.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum()
        val durationS: Double? = done.mapNotNull { it.durationS }.takeIf { it.isNotEmpty() }?.sum()
        /** Distance-weighted over the pieces with a distance and a split; one piece is just its own. */
        val splitS: Double? = done.filter { it.distanceM != null && it.distanceM > 0 && it.split != null }
            .takeIf { it.isNotEmpty() }
            ?.let { ps -> ps.sumOf { it.split!! * it.distanceM!! } / ps.sumOf { it.distanceM!! } }
        val bestSplitS: Double? = done.mapNotNull { it.bestSplitS ?: it.split }.filter { it > 0 }.minOrNull()
        /** Time-weighted where times are known, else a plain average. */
        val watts: Int? = done.filter { it.watts != null }.takeIf { it.isNotEmpty() }?.let { ps ->
            val timed = ps.filter { (it.durationS ?: 0.0) > 0 }
            if (timed.size == ps.size) (timed.sumOf { it.watts!! * it.durationS!! } / timed.sumOf { it.durationS!! }).roundToInt()
            else ps.map { it.watts!! }.average().roundToInt()
        }
        val kcal: Int? = done.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum()
        val avgHr: Int? = done.mapNotNull { it.avgHr }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        val format: Format? = Format.of(durationS, distanceM, style)
    }

    /** Groups pieces by session in date order, leaving out sessions with nothing filled in. */
    fun sessions(pieces: List<Piece>): List<Session> =
        pieces.groupBy { it.loggedId }.values
            .filter { ps -> ps.any { it.done } }
            .map { ps -> ps.first().let { Session(it.date, it.exerciseId, it.name, it.loggedId, ps) } }
            .sortedBy { it.date }

    data class Pr(val session: Session, val distance: Boolean, val split: Boolean, val watts: Boolean, val kcal: Boolean = false)

    /**
     * Sessions that beat every earlier session of the same exercise and [Format]: more distance, a
     * faster (lower) average pace, more average watts, or more calories. Like lifting PRs: judged
     * against all history, the first of a format is the baseline, and a tie isn't a PR. Calories
     * count only for time-only exercises (an elliptical's "30 min"), where they're the one measure of effort.
     */
    fun prs(sessions: List<Session>): List<Pr> {
        data class Best(var distance: Int? = null, var split: Double? = null, var watts: Int? = null, var kcal: Int? = null)
        val best = mutableMapOf<Pair<Long, String>, Best>()
        val out = mutableListOf<Pr>()
        for (s in sessions.sortedBy { it.date }) {
            val key = s.exerciseId to (s.format?.key ?: "")
            val first = key !in best
            val b = best.getOrPut(key) { Best() }
            val kcal = s.kcal.takeIf { !s.style.hasDistance }
            val d = !first && s.distanceM != null && (b.distance == null || s.distanceM > b.distance!!)
            val sp = !first && s.splitS != null && (b.split == null || s.splitS < b.split!!)
            val w = !first && s.watts != null && (b.watts == null || s.watts > b.watts!!)
            val k = !first && kcal != null && (b.kcal == null || kcal > b.kcal!!)
            if (d || sp || w || k) out += Pr(s, d, sp, w, k)
            s.distanceM?.let { if (b.distance == null || it > b.distance!!) b.distance = it }
            s.splitS?.let { if (b.split == null || it < b.split!!) b.split = it }
            s.watts?.let { if (b.watts == null || it > b.watts!!) b.watts = it }
            kcal?.let { if (b.kcal == null || it > b.kcal!!) b.kcal = it }
        }
        return out
    }

    data class WeekTotal(val monday: LocalDate, val metres: Int, val seconds: Double)

    /** Distance and time per Monday-to-Sunday week, every week touching [from]..[to], empty weeks included. */
    fun weeklyTotals(sessions: List<Session>, from: LocalDate, to: LocalDate): List<WeekTotal> {
        val byWeek = sessions.filter { it.date in Metrics.weekOf(from)..to }.groupBy { Metrics.weekOf(it.date) }
        return generateSequence(Metrics.weekOf(from)) { it.plusWeeks(1) }.takeWhile { it <= to }
            .map { w -> byWeek[w].orEmpty().let { s -> WeekTotal(w, s.sumOf { it.distanceM ?: 0 }, s.sumOf { it.durationS ?: 0.0 }) } }
            .toList()
    }

    /**
     * "3,260 m · 15:07 · 2:10/500m · 158 W", "5.0 km · 26:00 · 5:12/km", "30 min · 250 kcal":
     * whatever is known and the style shows, in that order. Calories only where there's no
     * distance, since that's all a time-only machine has to say.
     */
    fun summary(style: Style, distanceM: Int?, durationS: Double?, splitS: Double?, watts: Int? = null, kcal: Int? = null): String {
        val distance = distanceM?.takeIf { style.hasDistance }
        return listOfNotNull(
            distance?.let { distanceText(it, style) },
            durationS?.let(::durationText),
            splitS?.takeIf { distance != null }?.let { style.pace.text(it) },
            watts?.takeIf { style.watts }?.let { "$it W" },
            kcal?.takeIf { distance == null }?.let { "$it kcal" },
        ).joinToString(" · ")
    }

    /**
     * The short form for the lock screen: distance and pace ("5.0 km · 5:12/km"), or without a
     * distance, time and calories ("30 min · 250 kcal").
     */
    fun headline(style: Style, distanceM: Int?, durationS: Double?, splitS: Double?, kcal: Int?): String {
        val distance = distanceM?.takeIf { style.hasDistance }
        if (distance != null) {
            val split = effectiveSplit(splitS, durationS, distance)
            return listOfNotNull(distanceText(distance, style), split?.let { style.pace.text(it) }).joinToString(" · ")
        }
        return listOfNotNull(durationS?.let(::durationText), kcal?.let { "$it kcal" }).joinToString(" · ")
    }
}

/** A cardio piece as typed or read from a photo, before it's saved; see app.diurna.data.DayRepository.updatePiece. */
data class CardioPiece(
    val distanceM: Int? = null,
    val durationS: Double? = null,
    val splitS: Double? = null,
    val bestSplitS: Double? = null,
    val watts: Int? = null,
    val kcal: Int? = null,
    val avgHr: Int? = null,
) {
    /** Zero or out-of-range values become null: a blank field and a misread mean the same thing. */
    fun clean(): CardioPiece = CardioPiece(
        distanceM = distanceM?.takeIf { it in 1..Cardio.MAX_DISTANCE_M },
        durationS = durationS?.takeIf { it > 0 && it <= Cardio.MAX_DURATION_S },
        splitS = splitS?.takeIf { it > 0 && it <= Cardio.MAX_SPLIT_S },
        bestSplitS = bestSplitS?.takeIf { it > 0 && it <= Cardio.MAX_SPLIT_S },
        watts = watts?.takeIf { it in 1..Cardio.MAX_WATTS },
        kcal = kcal?.takeIf { it in 1..Cardio.MAX_KCAL },
        avgHr = avgHr?.takeIf { it in 1..Cardio.MAX_HR },
    )

    val isEmpty: Boolean get() = this == CardioPiece()
}
