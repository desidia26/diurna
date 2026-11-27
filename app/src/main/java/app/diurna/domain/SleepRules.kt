package app.diurna.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Turns sleep sessions and overnight samples (from Health Connect, untrusted) into nights.
 *
 * - The date is the wake day: the local date the session ends.
 * - Woke is the end of the last sleep stage; the session end is when you got up. Sources like
 *   Oura often end the session well after waking, so time in bed and efficiency run from bed to
 *   woke, and the rest is left out.
 * - A wake counts when you're awake for [MIN_WAKE] or more after first falling asleep, and before
 *   the last sleep stage. Shorter stirs show in the stages but aren't counted.
 * - Per source and date, the longest session ending 03:00–14:00 is the main night; others are naps.
 * - Unknown stays null, never 0.
 */
object SleepRules {
    enum class Type(val key: String) { MAIN("main"), NAP("nap") }

    data class Stage(val start: Instant, val end: Instant, val code: Int)

    data class Session(
        val id: String,
        val origin: String,
        val start: Instant,
        val end: Instant,
        val startOffset: ZoneOffset?,
        val endOffset: ZoneOffset?,
        val stages: List<Stage>,
    )

    data class Sample(val origin: String, val at: Instant, val value: Double)

    /** A stage in seconds from bed start. */
    data class StageSpan(val startS: Int, val endS: Int, val code: Int)

    /** A sample in seconds from bed start. */
    data class Point(val offsetS: Int, val value: Double)

    data class Night(
        val sourceId: String,
        val origin: String,
        val date: LocalDate,
        val type: Type,
        val bedStart: LocalDateTime,
        val wokeAt: LocalDateTime?,
        val bedEnd: LocalDateTime,
        /** Bed start to woke; bed start to bed end when there are no stages. */
        val timeInBedS: Long,
        val totalSleepS: Long?,
        val awakeS: Long?,
        val lightS: Long?,
        val deepS: Long?,
        val remS: Long?,
        val latencyS: Long?,
        val efficiency: Double?,
        val awakenings: Int?,
        val avgHr: Double?,
        val lowestHr: Double?,
        val avgHrv: Double?,
        val avgBreath: Double?,
        val avgSpo2: Double?,
        val stages: List<StageSpan>,
        val hr: List<Point>,
        val hrv: List<Point>,
    )

    // Health Connect's SleepSessionRecord stage codes.
    const val AWAKE = 1
    const val SLEEPING = 2
    const val OUT_OF_BED = 3
    const val LIGHT = 4
    const val DEEP = 5
    const val REM = 6
    const val AWAKE_IN_BED = 7
    private val AwakeCodes = setOf(AWAKE, OUT_OF_BED, AWAKE_IN_BED)
    private val SleepCodes = setOf(SLEEPING, LIGHT, DEEP, REM)

    val MIN_WAKE: Duration = Duration.ofMinutes(10)
    val MAX_SESSION: Duration = Duration.ofHours(24)
    const val UPDATABLE_DAYS = 3L
    const val HISTORY_DAYS = 30L
    const val MAX_STAGES = 500
    /** 5-minute points over the longest session. */
    const val MAX_POINTS = 24 * 12
    private const val BUCKET_S = 300

    private val MainEndsFrom: LocalTime = LocalTime.of(3, 0)
    private val MainEndsBy: LocalTime = LocalTime.of(14, 0)

    private val HrRange = 25.0..250.0
    private val HrvRange = 1.0..300.0
    private val BreathRange = 4.0..60.0
    private val Spo2Range = 70.0..100.0

    fun nights(
        sessions: List<Session>,
        hr: List<Sample>,
        hrv: List<Sample>,
        breath: List<Sample>,
        spo2: List<Sample>,
        fallbackZone: ZoneId,
    ): List<Night> {
        val nights = sessions.mapNotNull { night(it, hr, hrv, breath, spo2, fallbackZone) }
        // Real length, not local clock times: those differ on the night the clocks change.
        val length = sessions.associate { it.id to Duration.between(it.start, it.end) }
        val mains = nights
            .filter { it.bedEnd.toLocalTime() in MainEndsFrom..MainEndsBy }
            .groupBy { it.origin to it.date }
            .mapNotNull { (_, list) -> list.maxByOrNull { length.getValue(it.sourceId) }?.sourceId }
            .toSet()
        return nights.map { if (it.sourceId in mains) it else it.copy(type = Type.NAP) }
    }

    private fun night(
        s: Session, hr: List<Sample>, hrv: List<Sample>, breath: List<Sample>, spo2: List<Sample>, fallbackZone: ZoneId,
    ): Night? {
        val length = Duration.between(s.start, s.end)
        if (length <= Duration.ZERO || length > MAX_SESSION) return null
        val startZone: ZoneId = s.startOffset ?: fallbackZone
        val endZone: ZoneId = s.endOffset ?: fallbackZone
        fun localAtEnd(t: Instant) = LocalDateTime.ofInstant(t, endZone)
        fun sec(t: Instant) = Duration.between(s.start, t).seconds

        val stages = cleanStages(s)
        val sleep = stages.filter { it.code in SleepCodes }
        val onset = sleep.firstOrNull()?.start
        val woke = sleep.lastOrNull()?.end
        val inBedEnd = woke ?: s.end

        fun sum(codes: Set<Int>) = stages.filter { it.code in codes }.sumOf { Duration.between(it.start, it.end).seconds }
        val hasStages = woke != null
        val hasTypes = stages.any { it.code == LIGHT || it.code == DEEP || it.code == REM }
        val total = if (hasStages) sleep.sumOf { Duration.between(it.start, it.end).seconds } else null
        val timeInBed = sec(inBedEnd)

        val window = s.start..s.end
        fun valid(list: List<Sample>, range: ClosedFloatingPointRange<Double>) =
            list.filter { it.origin == s.origin && it.at in window && it.value in range }.sortedBy { it.at }
        val hrOk = valid(hr, HrRange)
        val hrvOk = valid(hrv, HrvRange)

        return Night(
            sourceId = s.id,
            origin = s.origin,
            date = localAtEnd(s.end).toLocalDate(),
            type = Type.MAIN,
            bedStart = LocalDateTime.ofInstant(s.start, startZone),
            wokeAt = woke?.let(::localAtEnd),
            bedEnd = localAtEnd(s.end),
            timeInBedS = timeInBed,
            totalSleepS = total,
            awakeS = if (hasStages) stages.filter { it.code in AwakeCodes && it.end <= inBedEnd }.sumOf { Duration.between(it.start, it.end).seconds } else null,
            lightS = if (hasTypes) sum(setOf(LIGHT)) else null,
            deepS = if (hasTypes) sum(setOf(DEEP)) else null,
            remS = if (hasTypes) sum(setOf(REM)) else null,
            latencyS = onset?.let(::sec),
            efficiency = if (total != null && timeInBed > 0) total.toDouble() / timeInBed else null,
            awakenings = if (hasStages) awakenings(stages, onset!!, woke) else null,
            avgHr = hrOk.map { it.value }.averageOrNull(),
            lowestHr = hrOk.minOfOrNull { it.value },
            avgHrv = hrvOk.map { it.value }.averageOrNull(),
            avgBreath = valid(breath, BreathRange).map { it.value }.averageOrNull(),
            avgSpo2 = valid(spo2, Spo2Range).map { it.value }.averageOrNull(),
            stages = stages.map { StageSpan(sec(it.start).toInt(), sec(it.end).toInt(), it.code) },
            hr = buckets(hrOk, s.start),
            hrv = buckets(hrvOk, s.start),
        )
    }

    /** In order, inside the session, no empty or backwards stages, capped. Unknown codes are dropped, so they count as neither sleep nor awake. */
    private fun cleanStages(s: Session): List<Stage> = s.stages
        .filter { it.code in SleepCodes || it.code in AwakeCodes }
        .map { Stage(maxOf(it.start, s.start), minOf(it.end, s.end), it.code) }
        .filter { it.end > it.start }
        .sortedBy { it.start }
        .take(MAX_STAGES)

    private fun awakenings(stages: List<Stage>, onset: Instant, woke: Instant): Int {
        var count = 0
        var runStart: Instant? = null
        var runEnd: Instant? = null
        fun close() {
            if (runStart != null && Duration.between(runStart, runEnd) >= MIN_WAKE) count++
            runStart = null; runEnd = null
        }
        for (st in stages) {
            if (st.end <= onset || st.start >= woke) continue
            if (st.code in AwakeCodes) {
                if (runStart == null) runStart = st.start
                runEnd = st.end
            } else close()
        }
        close()
        return count
    }

    /** Averaged into 5-minute buckets from bed start, capped. */
    private fun buckets(samples: List<Sample>, start: Instant): List<Point> = samples
        .groupBy { (Duration.between(start, it.at).seconds / BUCKET_S).toInt() }
        .toSortedMap()
        .map { (b, list) -> Point(b * BUCKET_S, list.map { it.value }.average()) }
        .take(MAX_POINTS)

    private fun List<Double>.averageOrNull() = if (isEmpty()) null else average()

    /** A stored night can still change until 3 days after its wake date, then it's frozen. */
    fun updatable(date: LocalDate, today: LocalDate) = !today.isAfter(date.plusDays(UPDATABLE_DAYS))

    /**
     * Sources sync late, so each read starts two days before the last one, or at the newest stored
     * night when that's earlier: a ring that hasn't synced for days still has those nights to send,
     * and a restored backup may be missing recent ones. Never before Health Connect's 30 days.
     */
    fun readFrom(lastRead: LocalDate?, newestStored: LocalDate?, today: LocalDate): LocalDate {
        val earliest = today.minusDays(HISTORY_DAYS)
        if (lastRead == null || newestStored == null) return earliest
        return maxOf(minOf(lastRead.minusDays(2), newestStored), earliest)
    }

    /**
     * Whose sleep shows: the app picked in Setup, else Oura when it has written any, else the app
     * with the most nights. [seen] is (origin, nights), most first.
     */
    fun pickOrigin(chosen: String?, seen: List<Pair<String, Int>>): String? = chosen
        ?: seen.firstOrNull { it.first == OURA }?.first
        ?: seen.maxByOrNull { it.second }?.first

    const val OURA = "com.ouraring.oura"
}
