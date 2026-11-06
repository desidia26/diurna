package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmTimingTest {
    private val nyc = ZoneId.of("America/New_York")
    private val chicago = ZoneId.of("America/Chicago")
    private val utc = ZoneId.of("UTC")

    @Test fun sameWallClockDiffersAcrossZones() {
        val at = LocalDateTime.of(2026, 6, 1, 6, 30)
        val nycMs = AlarmTiming.triggerEpochMilli(at, nyc)
        val chiMs = AlarmTiming.triggerEpochMilli(at, chicago)
        // Central is one hour behind Eastern in summer, so the same 06:30 local is later in UTC for Chicago.
        assertEquals(60 * 60 * 1000L, chiMs - nycMs)
    }

    @Test fun springForwardGapUsesZoneRules() {
        // 2026-03-08: clocks jump 02:00 -> 03:00 in America/New_York. 02:30 is invalid.
        val gap = LocalDateTime.of(2026, 3, 8, 2, 30)
        val ms = AlarmTiming.triggerEpochMilli(gap, nyc)
        val resolved = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), nyc)
        // java.time resolves the gap by shifting forward into the new offset.
        assertEquals(3, resolved.hour)
        assertTrue(resolved.offset.totalSeconds == -4 * 3600)
    }

    @Test fun fallBackOverlapPicksOffsetByZoneRules() {
        // 2026-11-01: 01:30 occurs twice in America/New_York.
        val overlap = LocalDateTime.of(2026, 11, 1, 1, 30)
        val ms = AlarmTiming.triggerEpochMilli(overlap, nyc)
        val resolved = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), nyc)
        assertEquals(1, resolved.hour)
        assertEquals(30, resolved.minute)
        // LocalDateTime.atZone picks the earlier offset (EDT, -4) before the repeated hour.
        assertEquals(-4 * 3600, resolved.offset.totalSeconds)
    }

    @Test fun timezoneChangeMovesTriggerInstant() {
        val at = LocalDateTime.of(2026, 7, 4, 5, 0)
        assertNotEquals(
            AlarmTiming.triggerEpochMilli(at, nyc),
            AlarmTiming.triggerEpochMilli(at, utc),
        )
    }

    @Test fun roundTripPreservesCivilTimeOutsideTransitions() {
        val at = LocalDateTime.of(2026, 4, 15, 6, 45)
        val ms = AlarmTiming.triggerEpochMilli(at, nyc)
        val back = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), nyc).toLocalDateTime()
        assertEquals(at, back)
    }
}
