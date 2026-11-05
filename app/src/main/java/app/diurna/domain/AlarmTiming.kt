package app.diurna.domain

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Converts a wall-clock alarm time into the epoch millis AlarmManager needs.
 * Uses the given zone so DST gaps/overlaps and timezone changes stay explicit and testable.
 */
object AlarmTiming {
    fun triggerEpochMilli(at: LocalDateTime, zone: ZoneId): Long =
        at.atZone(zone).toInstant().toEpochMilli()
}
