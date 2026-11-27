package app.diurna.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What Health Connect holds, for Setup → Data → Health Connect: record types and the apps that
 * wrote them, with counts and times only. No values, so the list can be copied into a bug report.
 */
object HealthInventory {
    /** The record types sleep reads; the order is the list's order. */
    enum class Kind(val label: String) {
        SLEEP("Sleep session"),
        HEART_RATE("Heart rate"),
        HRV("HRV (RMSSD)"),
        BREATHING("Breathing rate"),
        SPO2("SpO2"),
        RESTING_HR("Resting heart rate"),
    }

    /** One record as read, cut down to what the list shows. */
    data class Seen(
        val kind: Kind,
        val origin: String,
        val end: Instant,
        val stages: List<Int> = emptyList(),
        val samples: Int = 0,
    )

    data class Row(
        val kind: Kind,
        val origin: String,
        val count: Int,
        val newest: Instant,
        val stagesPerRecord: IntRange?,
        val stageTypes: List<Int>,
        val samplesPerRecord: IntRange?,
        /** The read stopped at its cap, so there are more than [count]. */
        val capped: Boolean,
    )

    fun rows(seen: List<Seen>, capped: Set<Kind> = emptySet()): List<Row> =
        seen.groupBy { it.kind to it.origin }
            .map { (key, list) ->
                val (kind, origin) = key
                val stageCounts = list.map { it.stages.size }.filter { it > 0 }
                val sampleCounts = list.map { it.samples }.filter { it > 0 }
                Row(
                    kind = kind,
                    origin = origin,
                    count = list.size,
                    newest = list.maxOf { it.end },
                    stagesPerRecord = stageCounts.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
                    stageTypes = list.flatMap { it.stages }.distinct().sortedBy { StageOrder.indexOf(it).let { i -> if (i < 0) 99 else i } },
                    samplesPerRecord = sampleCounts.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() },
                    capped = kind in capped,
                )
            }
            .sortedWith(compareBy<Row> { it.kind.ordinal }.thenByDescending { it.count }.thenBy { it.origin })

    // Health Connect's SleepSessionRecord stage codes, in the order a night reads.
    private val StageOrder = listOf(1, 7, 3, 2, 4, 5, 6, 0)

    fun stageName(code: Int): String = when (code) {
        1 -> "awake"
        2 -> "sleeping"
        3 -> "out of bed"
        4 -> "light"
        5 -> "deep"
        6 -> "REM"
        7 -> "awake in bed"
        else -> "unknown"
    }

    /** Apps people will recognise; anything else shows its package name. */
    fun sourceName(pkg: String): String = when {
        pkg == "com.ouraring.oura" -> "Oura"
        pkg.startsWith("com.android.healthconnect.phone") -> "This phone"
        pkg == "com.google.android.apps.fitness" -> "Google Fit"
        pkg == "com.google.android.apps.healthdata" -> "Health Connect"
        pkg == "com.fitbit.FitbitMobile" -> "Fitbit"
        pkg == "com.sec.android.app.shealth" -> "Samsung Health"
        pkg == "com.garmin.android.apps.connectmobile" -> "Garmin Connect"
        pkg == "com.whoop.android" -> "WHOOP"
        else -> pkg
    }

    private fun range(r: IntRange) = if (r.first == r.last) "${r.first}" else "${r.first}–${r.last}"

    /** The extra line under a row: stages for sleep, samples for a series, else nothing. */
    fun detail(row: Row): String = when {
        row.kind == Kind.SLEEP && row.stagesPerRecord == null -> "no stages"
        row.stagesPerRecord != null ->
            "${range(row.stagesPerRecord)} stages each · " + row.stageTypes.joinToString(", ") { stageName(it) }
        row.samplesPerRecord != null -> "${range(row.samplesPerRecord)} samples each"
        else -> ""
    }

    fun countLabel(row: Row) = "${row.count}" + if (row.capped) "+" else ""

    private val Stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun text(rows: List<Row>, empty: List<Kind>, noPermission: List<Kind>, zone: ZoneId, failed: List<Kind> = emptyList()): String = buildList {
        add("Health Connect, last 7 days")
        rows.forEach { r ->
            add(listOf(
                r.kind.label, sourceName(r.origin), countLabel(r), "newest " + Stamp.format(r.newest.atZone(zone)), detail(r),
            ).filter { it.isNotEmpty() }.joinToString(" · "))
        }
        if (empty.isNotEmpty()) add("None: " + empty.joinToString(", ") { it.label })
        if (noPermission.isNotEmpty()) add("No permission: " + noPermission.joinToString(", ") { it.label })
        if (failed.isNotEmpty()) add("Couldn't read: " + failed.joinToString(", ") { it.label })
    }.joinToString("\n")
}
