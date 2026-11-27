package app.diurna.domain

import app.diurna.data.SleepPeriodEntity
import app.diurna.domain.SleepRules.Point
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** How stored nights read on screen: the Morning row, the Week chip, the detail screen. */
object SleepView {
    /** One wake day's sleep from one source: the main night, and naps in order. */
    data class Day(val main: SleepPeriodEntity?, val naps: List<SleepPeriodEntity>) {
        val empty get() = main == null && naps.isEmpty()
    }

    data class Row(val title: String, val detail: String?)

    private val Stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun day(rows: List<SleepPeriodEntity>, date: LocalDate, origin: String?): Day {
        val mine = rows.filter { it.date == date.toString() && (origin == null || it.origin == origin) }
        return Day(
            mine.firstOrNull { it.type == SleepRules.Type.MAIN.key },
            mine.filter { it.type == SleepRules.Type.NAP.key }.sortedBy { it.bedStart },
        )
    }

    fun hm(seconds: Long): String {
        val m = (seconds + 30) / 60
        return if (m < 60) "${m}m" else "${m / 60}h ${(m % 60).toString().padStart(2, '0')}m"
    }

    private fun clock(stamp: String) = stamp.takeLast(5)

    /** Asleep when there are stages, else time in bed. */
    fun asleep(n: SleepPeriodEntity): String =
        n.totalSleepS?.let { hm(it) + " asleep" } ?: (hm(n.timeInBedS) + " in bed")

    fun times(n: SleepPeriodEntity): String {
        val woke = n.wokeAt ?: return "${clock(n.bedStart)} → ${clock(n.bedEnd)} · no stages shared"
        val up = if (n.bedEnd != woke) " · up ${clock(n.bedEnd)}" else ""
        return "${clock(n.bedStart)} → woke ${clock(woke)}$up"
    }

    /** Last night or the one before, which the source may not have sent yet. */
    fun syncPending(date: LocalDate, today: LocalDate) = date >= today.minusDays(1) && date <= today

    private fun napLength(n: SleepPeriodEntity) = n.totalSleepS ?: n.timeInBedS

    fun row(d: Day, date: LocalDate, today: LocalDate): Row {
        if (d.empty) {
            // Oura sends a night after its app syncs, often after the porch scan.
            return when {
                date > today -> Row("Not yet", null)
                syncPending(date, today) -> Row("Not synced yet", null)
                else -> Row("No sleep recorded", null)
            }
        }
        val naps = d.naps.sumOf(::napLength).takeIf { it > 0 }?.let { hm(it) + " nap" }
        val title = when {
            d.main == null -> naps!!
            naps == null -> asleep(d.main)
            else -> asleep(d.main) + " + " + naps
        }
        val detail = (listOfNotNull(d.main?.let(::times)) + d.naps.map { "nap ${clock(it.bedStart)} → ${clock(it.bedEnd)}" })
            .joinToString(" · ")
        return Row(title, detail)
    }

    /** For the folded Morning card; null without a main night. */
    fun summary(d: Day): String? = chip(d)?.let { "$it sleep" }

    fun chip(d: Day): String? = d.main?.let { hm(it.totalSleepS ?: it.timeInBedS) }

    private fun at(stamp: String): LocalDateTime? = runCatching { LocalDateTime.parse(stamp, Stamp) }.getOrNull()

    /** Woke to up: lying awake before getting up, left out of time in bed. */
    fun awakeAfterWaking(n: SleepPeriodEntity): Long? {
        val woke = n.wokeAt?.let(::at) ?: return null
        val up = at(n.bedEnd) ?: return null
        return Duration.between(woke, up).seconds.takeIf { it > 0 }
    }

    /**
     * Bed to up in real seconds, for laying out the stage chart. Time in bed is real seconds and
     * woke and up share a zone, so this holds on the night the clocks change, where bed to up in
     * local clock times is an hour off.
     */
    fun lengthS(n: SleepPeriodEntity): Long = n.timeInBedS + (awakeAfterWaking(n) ?: 0)

    /** 5-minute slots from bed start; a slot the source sent nothing for is null, so the line breaks. */
    fun slots(points: List<Point>): List<Double?> {
        if (points.isEmpty()) return emptyList()
        val bySlot = points.associate { it.offsetS / SLOT_S to it.value }
        return (0..bySlot.keys.max()).map { bySlot[it] }
    }

    const val SLOT_S = 300
}
