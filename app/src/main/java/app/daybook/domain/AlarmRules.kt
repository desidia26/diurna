package app.daybook.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The morning rules, kept free of Android so they can be unit tested.
 *
 * An alarm setting is either a time ("HH:mm") or [OFF]. A day's alarm comes from its
 * override if set, else the routine. Changes are allowed until [LOCK_HOUR] the day before;
 * after that the day is locked and the alarm that was armed (its frozen value) stands.
 */
object AlarmRules {
    const val OFF = "off"
    const val LOCK_HOUR = 22
    const val ON_TIME_MINUTES = 10
    private const val LOOKAHEAD_DAYS = 8L

    fun lockTime(date: LocalDate): LocalDateTime = date.minusDays(1).atTime(LOCK_HOUR, 0)

    fun isLocked(date: LocalDate, now: LocalDateTime): Boolean = !now.isBefore(lockTime(date))

    /** Override wins over the routine. */
    fun resolve(override: String?, routineAlarm: String): String = override ?: routineAlarm

    /**
     * The alarm that applies to a day. Once locked, a frozen value (what the scheduler armed
     * before the lock) wins, so routine edits after 10pm can't move tomorrow's alarm.
     */
    fun effective(
        date: LocalDate,
        now: LocalDateTime,
        override: String?,
        routineAlarm: String,
        frozen: String?,
    ): String = if (isLocked(date, now) && frozen != null) frozen else resolve(override, routineAlarm)

    data class DayAlarmInputs(val effective: String, val scanned: Boolean)

    data class NextAlarm(val date: LocalDate, val time: LocalTime) {
        val at: LocalDateTime get() = date.atTime(time)
    }

    /** The first alarm strictly after [now], skipping days that are off or already scanned. */
    fun nextAlarm(now: LocalDateTime, inputsFor: (LocalDate) -> DayAlarmInputs): NextAlarm? {
        for (i in 0 until LOOKAHEAD_DAYS) {
            val date = now.toLocalDate().plusDays(i)
            val inputs = inputsFor(date)
            if (inputs.effective == OFF || inputs.scanned) continue
            val time = LocalTime.parse(inputs.effective)
            if (date.atTime(time).isAfter(now)) return NextAlarm(date, time)
        }
        return null
    }

    /** How long an alarm rings before giving up. Past that, an unscanned morning is missed. */
    const val RING_MINUTES = 60L

    /**
     * PLANNED: a future day. UPCOMING: today, before the alarm. WAITING: today, alarm time passed
     * but still inside the ring window, so a scan can still happen.
     */
    enum class Morning { OFF, PLANNED, UPCOMING, WAITING, MISSED, ON_TIME, LATE }

    data class MorningResult(val status: Morning, val minutesLate: Int? = null) {
        /** Whether this morning counts toward on-time scoring. */
        val scored: Boolean get() = status in setOf(Morning.MISSED, Morning.ON_TIME, Morning.LATE)
    }

    fun morning(date: LocalDate, now: LocalDateTime, effective: String, scannedAt: String?): MorningResult {
        if (effective == OFF) return MorningResult(Morning.OFF)
        if (scannedAt == null) {
            val today = now.toLocalDate()
            val alarmAt = date.atTime(LocalTime.parse(effective))
            return MorningResult(
                when {
                    date.isAfter(today) -> Morning.PLANNED
                    date.isBefore(today) -> Morning.MISSED
                    now.isBefore(alarmAt) -> Morning.UPCOMING
                    now.isBefore(alarmAt.plusMinutes(RING_MINUTES)) -> Morning.WAITING
                    else -> Morning.MISSED
                }
            )
        }
        val late = minutesBetween(effective, scannedAt)
        return if (late <= ON_TIME_MINUTES) MorningResult(Morning.ON_TIME, late)
        else MorningResult(Morning.LATE, late)
    }

    private fun minutesBetween(from: String, to: String): Int =
        (LocalTime.parse(to).toSecondOfDay() - LocalTime.parse(from).toSecondOfDay()) / 60
}
