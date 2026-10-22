package app.diurna.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The phone's step sensor reports a running total since the last reboot. Diurna looks at it now
 * and then and turns the difference into steps for a day. These are the rules, kept free of
 * Android so every case is unit tested.
 */
object StepMath {
    /** What the sensor said at the last look. */
    data class State(val counter: Long, val at: LocalDateTime)

    /** Steps to add to [date] (possibly 0), and the state to remember for next time. */
    data class Result(val date: LocalDate, val add: Long, val next: State)

    /** More than this between two looks is a sensor glitch, not walking. */
    const val MAX_PLAUSIBLE = 60_000L

    /**
     * [bootedAt] is when the phone last started. A reboot resets the sensor to 0, so after one
     * every count it reports is new.
     *
     * Steps go to the day of the current look. Looking just before midnight (Diurna wakes at
     * 23:59) keeps late-evening steps on the right day; if that look is missed, overnight steps
     * land on the new day, which is almost always where they happened.
     */
    fun look(previous: State?, counter: Long, now: LocalDateTime, bootedAt: LocalDateTime?): Result {
        val next = State(counter, now)
        val today = now.toLocalDate()
        if (previous == null || counter < 0) return Result(today, 0, next) // first look sets the baseline
        val rebooted = counter < previous.counter || (bootedAt != null && bootedAt.isAfter(previous.at))
        val delta = if (rebooted) counter else counter - previous.counter
        val add = if (delta in 0..MAX_PLAUSIBLE) delta else 0
        return Result(today, add, next)
    }
}
