package app.diurna.domain

import app.diurna.data.SleepPeriodEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SleepMetricsTest {
    private val mon = LocalDate.of(2026, 11, 23)

    private fun night(
        date: LocalDate, origin: String = SleepRules.OURA, type: String = "main",
        bed: String = "22:00", woke: String? = "06:00", up: String = "06:30",
        asleep: Long? = 7 * 3600L, deep: Long? = 3600, light: Long? = 4 * 3600, rem: Long? = 2 * 3600,
        low: Double? = 50.0, hrv: Double? = 80.0,
    ) = SleepPeriodEntity(
        source = "health_connect", origin = origin, sourceId = "$origin$date$type", date = date.toString(), type = type,
        bedStart = "${date.minusDays(1)} $bed", wokeAt = woke?.let { "$date $it" }, bedEnd = "$date $up",
        timeInBedS = 8 * 3600, totalSleepS = asleep, deepS = deep, lightS = light, remS = rem,
        lowestHr = low, avgHrv = hrv, fetchedAt = 0,
    )

    @Test fun clockMinutesCountFromSixTheEveningBefore() {
        assertEquals(4 * 60 + 10, SleepMetrics.clockMin("${mon.minusDays(1)} 22:10", mon))
        assertEquals(12 * 60 + 15, SleepMetrics.clockMin("$mon 06:15", mon))
        assertEquals(12 * 60 + 45, SleepMetrics.clockMin("06:45", mon))
        assertNull(SleepMetrics.clockMin("nonsense", mon))
    }

    @Test fun dailyBarsLeaveGapsAndSkipNapsAndOtherSources() {
        val rows = listOf(
            night(mon), night(mon.plusDays(2)),
            night(mon.plusDays(1), type = "nap"), night(mon.plusDays(1), origin = "com.watch"),
        )
        val card = SleepMetrics.card(rows, SleepRules.OURA, mon, mon.plusDays(2), emptyMap(), emptySet(), daily = true)!!
        assertEquals(3, card.stages.size)
        assertEquals(listOf(1.0, 4.0, 2.0, 0.0), card.stages[0]!!.map { it })
        assertNull(card.stages[1])
        assertEquals(2, card.nights)
        assertEquals(7 * 3600L, card.averageAsleepS)
    }

    @Test fun aNightWithoutStageTypesIsOneUnknownBlock() {
        val rows = listOf(night(mon, deep = null, light = null, rem = null))
        val card = SleepMetrics.card(rows, SleepRules.OURA, mon, mon, emptyMap(), emptySet(), daily = true)!!
        assertEquals(listOf(0.0, 0.0, 0.0, 7.0), card.stages[0])
    }

    @Test fun bedAndWakeCarryAlarmAndPorchScan() {
        val card = SleepMetrics.card(
            listOf(night(mon)), SleepRules.OURA, mon, mon,
            mapOf(mon to SleepMetrics.Morning(alarm = "06:45", scannedAt = "06:49")), emptySet(), daily = true,
        )!!
        val b = card.bed[0]!!
        assertEquals(4 * 60, b.bed)
        assertEquals(12 * 60, b.woke)
        assertEquals(12 * 60 + 30, b.up)
        assertEquals(12 * 60 + 45, b.alarm)
        assertEquals(12 * 60 + 49, b.scan)
    }

    @Test fun longerRangesAverageByWeekAndMarkDeloads() {
        val rows = (0L..13L).map { night(mon.plusDays(it), asleep = if (it < 7) 6 * 3600L else 8 * 3600L, low = if (it < 7) 50.0 else 54.0) }
        val card = SleepMetrics.card(rows, SleepRules.OURA, mon, mon.plusDays(13), emptyMap(), setOf(mon.plusDays(7)), daily = false)!!
        assertEquals(2, card.stages.size)
        assertEquals(listOf(false, true), card.deload)
        assertEquals(listOf(50.0, 54.0), card.lowestHr)
        assertEquals(7 * 3600L, card.averageAsleepS)
    }

    @Test fun nothingStoredMeansNoCard() {
        assertNull(SleepMetrics.card(emptyList(), SleepRules.OURA, mon, mon.plusDays(6), emptyMap(), emptySet(), daily = true))
    }

    @Test fun dailyDeloadMarksEveryDayOfTheWeek() {
        val card = SleepMetrics.card(listOf(night(mon)), SleepRules.OURA, mon, mon.plusDays(8), emptyMap(), setOf(mon), daily = true)!!
        assertTrue(card.deload.take(7).all { it })
        assertEquals(listOf(false, false), card.deload.drop(7))
    }
}
