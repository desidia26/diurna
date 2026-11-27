package app.diurna.domain

import app.diurna.data.SleepPeriodEntity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The Metrics Sleep card, worked out from stored main nights on the fly. One bar per night over
 * 4 weeks, weekly averages past that; a night with no data is a gap, never a zero.
 */
object SleepMetrics {
    /** The wake day's alarm and porch scan, "HH:mm". */
    data class Morning(val alarm: String?, val scannedAt: String?)

    /** Clock positions in minutes from 18:00 the evening before the wake day. */
    data class Bed(val bed: Int, val woke: Int?, val up: Int, val alarm: Int?, val scan: Int?)

    data class Card(
        /** Hours of deep, light, REM, and sleep of unknown stage, per bar; null is a gap. */
        val stages: List<List<Double>?>,
        val bed: List<Bed?>,
        val lowestHr: List<Double?>,
        val hrv: List<Double?>,
        /** Bars in a deload week, outlined on the chart. */
        val deload: List<Boolean>,
        val averageAsleepS: Long?,
        val nights: Int,
        val days: Int,
        val daily: Boolean,
    )

    private val Stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private const val EVENING_H = 18L

    /** [t] as "yyyy-MM-dd HH:mm", or "HH:mm" on [wakeDay]; minutes from 18:00 the day before. */
    fun clockMin(t: String, wakeDay: LocalDate): Int? {
        val at = runCatching { LocalDateTime.parse(t, Stamp) }.getOrNull()
            ?: runCatching { wakeDay.atTime(LocalTime.parse(t)) }.getOrNull()
            ?: return null
        return Duration.between(wakeDay.minusDays(1).atTime(EVENING_H.toInt(), 0), at).toMinutes().toInt()
    }

    fun card(
        rows: List<SleepPeriodEntity>,
        origin: String?,
        from: LocalDate,
        to: LocalDate,
        mornings: Map<LocalDate, Morning>,
        deloads: Set<LocalDate>,
        daily: Boolean,
    ): Card? {
        val mains = rows
            .filter { it.type == SleepRules.Type.MAIN.key && (origin == null || it.origin == origin) }
            .mapNotNull { r -> runCatching { LocalDate.parse(r.date) }.getOrNull()?.takeIf { it in from..to }?.let { it to r } }
            .toMap()
        if (mains.isEmpty()) return null
        val dates = Metrics.days(from, to)

        fun stages(r: SleepPeriodEntity): List<Double> {
            val asleep = (r.totalSleepS ?: r.timeInBedS) / 3600.0
            val typed = listOf(r.deepS, r.lightS, r.remS)
            if (typed.any { it == null }) return listOf(0.0, 0.0, 0.0, asleep)
            val h = typed.map { it!! / 3600.0 }
            return h + (asleep - h.sum()).coerceAtLeast(0.0)
        }
        fun bed(d: LocalDate, r: SleepPeriodEntity): Bed? {
            val m = mornings[d]
            return Bed(
                bed = clockMin(r.bedStart, d) ?: return null,
                woke = r.wokeAt?.let { clockMin(it, d) },
                up = clockMin(r.bedEnd, d) ?: return null,
                alarm = m?.alarm?.let { clockMin(it, d) },
                scan = m?.scannedAt?.let { clockMin(it, d) },
            )
        }
        val asleep = mains.values.map { it.totalSleepS ?: it.timeInBedS }
        val avg = asleep.average().toLong()

        if (daily) {
            return Card(
                stages = dates.map { d -> mains[d]?.let(::stages) },
                bed = dates.map { d -> mains[d]?.let { bed(d, it) } },
                lowestHr = dates.map { mains[it]?.lowestHr },
                hrv = dates.map { mains[it]?.avgHrv },
                deload = dates.map { Metrics.weekOf(it) in deloads },
                averageAsleepS = avg, nights = mains.size, days = dates.size, daily = true,
            )
        }
        val weeks = dates.groupBy { Metrics.weekOf(it) }
        fun List<Double>.avgOrNull() = if (isEmpty()) null else average()
        return Card(
            stages = weeks.values.map { days ->
                val s = days.mapNotNull { mains[it] }.map(::stages)
                if (s.isEmpty()) null else (0..3).map { i -> s.map { it[i] }.average() }
            },
            bed = weeks.values.map { days ->
                val b = days.mapNotNull { d -> mains[d]?.let { bed(d, it) } }
                if (b.isEmpty()) null else Bed(
                    bed = b.map { it.bed }.average().toInt(),
                    woke = b.mapNotNull { it.woke }.map(Int::toDouble).avgOrNull()?.toInt(),
                    up = b.map { it.up }.average().toInt(),
                    alarm = b.mapNotNull { it.alarm }.map(Int::toDouble).avgOrNull()?.toInt(),
                    scan = b.mapNotNull { it.scan }.map(Int::toDouble).avgOrNull()?.toInt(),
                )
            },
            lowestHr = weeks.values.map { days -> days.mapNotNull { mains[it]?.lowestHr }.avgOrNull() },
            hrv = weeks.values.map { days -> days.mapNotNull { mains[it]?.avgHrv }.avgOrNull() },
            deload = weeks.keys.map { it in deloads },
            averageAsleepS = avg, nights = mains.size, days = dates.size, daily = false,
        )
    }

    /** A clock position back to "HH:mm". */
    fun clock(min: Int): String {
        val m = Math.floorMod(min + EVENING_H.toInt() * 60, 24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
