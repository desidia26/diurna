package app.diurna.domain

import app.diurna.domain.SleepRules.Sample
import app.diurna.domain.SleepRules.Session
import app.diurna.domain.SleepRules.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class SleepRulesTest {
    private val est = ZoneOffset.ofHours(-5)
    private val oura = "com.ouraring.oura"
    private fun local(s: String): Instant = LocalDateTime.parse(s).toInstant(est)

    /**
     * A night in 5-minute steps, like Oura writes: a = awake, l = light, d = deep, r = REM.
     * Health Connect codes: awake 1, light 4, deep 5, REM 6.
     */
    private fun night(start: String, hypnogram: String, id: String = "n1", origin: String = oura): Session {
        val t0 = local(start)
        val stages = mutableListOf<Stage>()
        var i = 0
        while (i < hypnogram.length) {
            var j = i
            while (j < hypnogram.length && hypnogram[j] == hypnogram[i]) j++
            val code = when (hypnogram[i]) { 'a' -> 1; 'l' -> 4; 'd' -> 5; 'r' -> 6; else -> 0 }
            stages += Stage(t0.plusSeconds(i * 300L), t0.plusSeconds(j * 300L), code)
            i = j
        }
        return Session(id, origin, t0, t0.plusSeconds(hypnogram.length * 300L), est, est, stages)
    }

    // 22:10 start: 10 min awake, sleep with two 5-minute stirs, a 40-minute wake, then 35 min awake before getting up.
    private val typical = "aa" + "ll" + "ddddddddd" + "ll" + "a" + "lll" + "dddd" + "a" + "llll" + "rrr" + "ddddd" +
        "lllllll" + "aaaaaaaa" + "lllllllll" + "rrrrrrrrrr" + "llll" + "dd" + "rrrrrrr" + "llll" + "a" + "rrrrr" + "llll" + "aaaaaaa"

    private fun one(s: Session, hr: List<Sample> = emptyList(), hrv: List<Sample> = emptyList()) =
        SleepRules.nights(listOf(s), hr = hr, hrv = hrv, breath = emptyList(), spo2 = emptyList(), fallbackZone = est).single()

    @Test fun wakeIsTheLastSleepStageAndUpIsTheSessionEnd() {
        val n = one(night("2026-11-25T22:10", typical))
        assertEquals(LocalDate.parse("2026-11-26"), n.date)
        assertEquals(LocalDateTime.parse("2026-11-25T22:10"), n.bedStart)
        assertEquals(LocalDateTime.parse("2026-11-26T06:15"), n.wokeAt)
        assertEquals(LocalDateTime.parse("2026-11-26T06:50"), n.bedEnd)
    }

    @Test fun timeInBedRunsFromBedToWakeAndLeavesOutTheTimeBeforeGettingUp() {
        val n = one(night("2026-11-25T22:10", typical))
        assertEquals(485 * 60L, n.timeInBedS)
        assertEquals(420 * 60L, n.totalSleepS)
        assertEquals(65 * 60L, n.awakeS)
        assertEquals(420.0 / 485.0, n.efficiency!!, 1e-9)
    }

    @Test fun stageTotalsAndTimeToFallAsleep() {
        val n = one(night("2026-11-25T22:10", typical))
        assertEquals(10 * 60L, n.latencyS)
        assertEquals((2 + 2 + 3 + 4 + 7 + 9 + 4 + 4 + 4) * 300L, n.lightS)
        assertEquals((9 + 4 + 5 + 2) * 300L, n.deepS)
        assertEquals((3 + 10 + 7 + 5) * 300L, n.remS)
        assertEquals(n.totalSleepS, n.lightS!! + n.deepS!! + n.remS!!)
    }

    @Test fun onlyWakesOfTenMinutesOrMoreCount() {
        // Two 5-minute stirs and one 40-minute wake; the awake time at either end never counts.
        assertEquals(1, one(night("2026-11-25T22:10", typical)).awakenings)
    }

    @Test fun awakeStagesNextToEachOtherCountAsOneWake() {
        // Some sources split one wake into pieces, or mark part of it "awake in bed" (7).
        val s = night("2026-11-25T23:00", "llll" + "aa" + "llll")
        val split = s.copy(stages = s.stages.flatMap { st ->
            if (st.code != 1) listOf(st) else listOf(st.copy(end = st.start.plusSeconds(300)), st.copy(start = st.start.plusSeconds(300), code = 7))
        })
        assertEquals(1, one(split).awakenings)
    }

    @Test fun aNightWithoutStagesKeepsOnlyWhatItKnows() {
        val s = Session("n", oura, local("2026-11-25T23:55"), local("2026-11-26T06:35"), est, est, emptyList())
        val n = one(s)
        assertEquals(400 * 60L, n.timeInBedS)
        assertNull(n.wokeAt)
        assertNull(n.totalSleepS)
        assertNull(n.awakeS)
        assertNull(n.latencyS)
        assertNull(n.efficiency)
        assertNull(n.awakenings)
        assertTrue(n.stages.isEmpty())
    }

    @Test fun stagesAreSortedClippedToTheSessionAndBadOnesDropped() {
        val s = night("2026-11-25T23:00", "llllddddll")
        val messy = s.copy(stages = listOf(
            Stage(s.start.minusSeconds(600), s.start.plusSeconds(1200), 4), // starts before bed: clipped
            s.stages[2], s.stages[1],
            Stage(s.start.plusSeconds(900), s.start.plusSeconds(600), 5),   // ends before it starts: dropped
            Stage(s.end.plusSeconds(60), s.end.plusSeconds(600), 4),        // after the session: dropped
        ))
        val n = one(messy)
        assertEquals(listOf(0, 1200, 2400), n.stages.map { it.startS })
        assertEquals(n.stages.sortedBy { it.startS }, n.stages)
    }

    @Test fun overlongOrBackwardsSessionsAreDropped() {
        val ok = night("2026-11-25T23:00", "llll")
        val backwards = ok.copy(id = "b", end = ok.start.minusSeconds(60))
        val days = ok.copy(id = "c", end = ok.start.plus(Duration.ofHours(25)))
        val nights = SleepRules.nights(listOf(ok, backwards, days), emptyList(), emptyList(), emptyList(), emptyList(), est)
        assertEquals(listOf(ok.id), nights.map { it.sourceId })
    }

    @Test fun theDateIsTheWakeDayAcrossMidnight() {
        assertEquals(LocalDate.parse("2026-11-26"), one(night("2026-11-25T23:30", "llllllll")).date)
        assertEquals(LocalDate.parse("2026-11-26"), one(night("2026-11-26T00:30", "llllllll")).date)
    }

    @Test fun localTimesUseTheRecordsOffsetNotThePhonesZone() {
        val pst = ZoneOffset.ofHours(-8)
        val s = night("2026-11-25T22:10", "llll").let { it.copy(startOffset = pst, endOffset = pst) }
        val n = SleepRules.nights(listOf(s), emptyList(), emptyList(), emptyList(), emptyList(), est).single()
        assertEquals(LocalDateTime.parse("2026-11-25T19:10"), n.bedStart)
    }

    @Test fun theLongestSessionEndingInTheMorningIsTheMainNightAndTheRestAreNaps() {
        val main = night("2026-11-25T22:10", typical, id = "main")
        val early = night("2026-11-25T20:00", "llllllll", id = "early")       // ends 20:40: a nap
        val afternoon = night("2026-11-26T14:10", "llllll", id = "afternoon") // ends 14:40: a nap
        val shortMorning = night("2026-11-26T07:30", "llllll", id = "doze")   // ends 08:00, shorter than main
        val nights = SleepRules.nights(listOf(early, main, afternoon, shortMorning), emptyList(), emptyList(), emptyList(), emptyList(), est)
        assertEquals(mapOf("main" to SleepRules.Type.MAIN, "early" to SleepRules.Type.NAP, "afternoon" to SleepRules.Type.NAP, "doze" to SleepRules.Type.NAP),
            nights.associate { it.sourceId to it.type })
        assertEquals(LocalDate.parse("2026-11-25"), nights.single { it.sourceId == "early" }.date)
    }

    @Test fun eachSourceGetsItsOwnMainNight() {
        val a = night("2026-11-25T22:10", typical, id = "a")
        val b = night("2026-11-25T22:30", "l".repeat(96), id = "b", origin = "com.example.watch") // ends 06:30
        val nights = SleepRules.nights(listOf(a, b), emptyList(), emptyList(), emptyList(), emptyList(), est)
        assertTrue(nights.all { it.type == SleepRules.Type.MAIN })
    }

    @Test fun heartRateComesFromTheSameSourceInsideTheSession() {
        val s = night("2026-11-25T23:00", "llllllllllll") // 23:00–00:00
        val hr = listOf(
            Sample(oura, local("2026-11-25T22:50"), 40.0),               // before bed: ignored
            Sample(oura, local("2026-11-25T23:05"), 62.0),
            Sample(oura, local("2026-11-25T23:10"), 54.0),
            Sample(oura, local("2026-11-25T23:15"), 300.0),              // out of range: dropped
            Sample("com.example.watch", local("2026-11-25T23:20"), 45.0), // another source: ignored
            Sample(oura, local("2026-11-25T23:20"), 58.0),
        )
        val n = one(s, hr = hr)
        assertEquals(54.0, n.lowestHr!!, 0.0)
        assertEquals(58.0, n.avgHr!!, 1e-9)
        assertEquals(listOf(300, 600, 1200), n.hr.map { it.offsetS })
    }

    @Test fun heartRateIsAveragedIntoFiveMinuteBuckets() {
        val s = night("2026-11-25T23:00", "llllllllllll")
        val hr = (0 until 60).map { Sample(oura, s.start.plusSeconds(it * 60L), 50.0 + (it % 5)) }
        val n = one(s, hr = hr)
        assertEquals(12, n.hr.size)
        assertEquals(0, n.hr.first().offsetS)
        assertEquals(52.0, n.hr.first().value, 1e-9)
        assertEquals(50.0, n.lowestHr!!, 0.0)
    }

    @Test fun hrvOutsideItsRangeIsDropped() {
        val s = night("2026-11-25T23:00", "llllllllllll")
        val hrv = listOf(Sample(oura, local("2026-11-25T23:05"), 0.5), Sample(oura, local("2026-11-25T23:10"), 40.0), Sample(oura, local("2026-11-25T23:15"), 60.0))
        val n = one(s, hrv = hrv)
        assertEquals(50.0, n.avgHrv!!, 1e-9)
        assertEquals(2, n.hrv.size)
    }

    @Test fun noSamplesMeansNullNotZero() {
        val n = one(night("2026-11-25T23:00", "llll"))
        assertNull(n.avgHr); assertNull(n.lowestHr); assertNull(n.avgHrv); assertNull(n.avgBreath); assertNull(n.avgSpo2)
    }

    @Test fun breathingAndSpo2KeepOnlyPlausibleValues() {
        val s = night("2026-11-25T23:00", "llllllllllll")
        val breath = listOf(Sample(oura, local("2026-11-25T23:05"), 14.0), Sample(oura, local("2026-11-25T23:10"), 90.0))
        val spo2 = listOf(Sample(oura, local("2026-11-25T23:05"), 96.0), Sample(oura, local("2026-11-25T23:10"), 40.0))
        val n = SleepRules.nights(listOf(s), emptyList(), emptyList(), breath, spo2, est).single()
        assertEquals(14.0, n.avgBreath!!, 0.0)
        assertEquals(96.0, n.avgSpo2!!, 0.0)
    }

    @Test fun listsAreCapped() {
        val s = night("2026-11-25T22:00", "l".repeat(200))
        val many = s.copy(stages = (0 until 2000).map { Stage(s.start.plusSeconds(it * 30L), s.start.plusSeconds(it * 30L + 30), if (it % 2 == 0) 4 else 5) })
        val n = one(many)
        assertTrue(n.stages.size <= SleepRules.MAX_STAGES)
    }

    @Test fun aNightCanChangeForThreeDaysAfterItsWakeDate() {
        val wake = LocalDate.parse("2026-11-26")
        assertTrue(SleepRules.updatable(wake, wake))
        assertTrue(SleepRules.updatable(wake, wake.plusDays(3)))
        assertFalse(SleepRules.updatable(wake, wake.plusDays(4)))
    }

    @Test fun readsGoBackTwoDaysBeforeTheLastReadOr30DaysTheFirstTime() {
        val today = LocalDate.parse("2026-11-26")
        // Health Connect allows 30 days back without the history permission.
        assertEquals(LocalDate.parse("2026-10-27"), SleepRules.readFrom(null, null, today))
        assertEquals(LocalDate.parse("2026-11-24"), SleepRules.readFrom(today, today, today))
        // A gap reads everything since, up to the same 30 days.
        assertEquals(LocalDate.parse("2026-10-30"), SleepRules.readFrom(LocalDate.parse("2026-11-01"), today, today))
        assertEquals(LocalDate.parse("2026-10-27"), SleepRules.readFrom(LocalDate.parse("2026-09-01"), today, today))
        // Nothing stored yet: everything Health Connect has.
        assertEquals(LocalDate.parse("2026-10-27"), SleepRules.readFrom(today, null, today))
    }

    @Test fun aRingThatHasntSyncedForDaysIsReadFromItsNewestStoredNight() {
        val today = LocalDate.parse("2026-11-26")
        // Read hourly, but the newest night is from the 21st: those days may still arrive.
        assertEquals(LocalDate.parse("2026-11-21"), SleepRules.readFrom(today, LocalDate.parse("2026-11-21"), today))
        // A restored backup from long ago reads the full 30 days.
        assertEquals(LocalDate.parse("2026-10-27"), SleepRules.readFrom(today, LocalDate.parse("2026-08-01"), today))
    }

    @Test fun unknownStagesCountAsNeitherSleepNorAwake() {
        val s = night("2026-11-25T22:10", typical)
        // A 40-minute stage the source couldn't classify, after the last real one.
        val unknown = s.copy(end = s.end.plusSeconds(2400), stages = s.stages + Stage(s.end, s.end.plusSeconds(2400), 0))
        val n = one(unknown)
        assertEquals(LocalDateTime.parse("2026-11-26T06:15"), n.wokeAt)
        assertEquals(420 * 60L, n.totalSleepS)
        assertEquals(485 * 60L, n.timeInBedS)
        assertTrue(n.stages.none { it.code == 0 })
    }

    @Test fun theMainNightIsTheLongestInRealTimeWhenTheClocksChange() {
        val edt = ZoneOffset.ofHours(-4)
        // Fall back: 22:00 EDT to 07:00 EST is 10 real hours, 9 on the clock.
        val t0 = LocalDateTime.parse("2026-10-31T22:00").toInstant(edt)
        val long = Session("long", oura, t0, t0.plusSeconds(10 * 3600), edt, est, listOf(Stage(t0, t0.plusSeconds(10 * 3600), 4)))
        // 01:30 EST to 11:00 EST, after the change: 9h 30m, longer on the clock but shorter in real time.
        val t1 = LocalDateTime.parse("2026-11-01T01:30").toInstant(est)
        val short = Session("short", oura, t1, t1.plusSeconds(9 * 3600 + 30 * 60), est, est, listOf(Stage(t1, t1.plusSeconds(9 * 3600 + 30 * 60), 4)))
        val nights = SleepRules.nights(listOf(long, short), emptyList(), emptyList(), emptyList(), emptyList(), est)
        assertEquals("main", nights.single { it.sourceId == "long" }.type.key)
        assertEquals("nap", nights.single { it.sourceId == "short" }.type.key)
    }

    @Test fun ouraIsTheDefaultSourceThenWhicheverHasTheMostNights() {
        val watch = "com.example.watch"
        assertEquals(oura, SleepRules.pickOrigin(null, listOf(watch to 9, oura to 2)))
        assertEquals(watch, SleepRules.pickOrigin(null, listOf(watch to 9, "com.example.other" to 2)))
        assertEquals(watch, SleepRules.pickOrigin(watch, listOf(oura to 9)))
        assertNull(SleepRules.pickOrigin(null, emptyList()))
    }
}
