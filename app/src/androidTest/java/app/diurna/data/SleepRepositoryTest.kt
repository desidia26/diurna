package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.domain.SleepJson
import app.diurna.domain.SleepRules.Sample
import app.diurna.domain.SleepRules.Session
import app.diurna.domain.SleepRules.Stage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class SleepRepositoryTest {
    private val t = TestDb()
    private val est = ZoneOffset.ofHours(-5)
    private val oura = "com.ouraring.oura"
    private var now: Instant = at("2026-11-26T08:00")
    private fun at(s: String): Instant = LocalDateTime.parse(s).toInstant(est)

    /** Fake source: whatever sessions are set; [failure] makes the read throw. */
    private val source = object : SleepSource {
        override val key = "health_connect"
        var sessions = listOf<Session>()
        var hr = listOf<Sample>()
        var failure: Exception? = null
        var complete = true
        val reads = mutableListOf<Pair<Instant, Instant>>()
        override suspend fun read(from: Instant, to: Instant): SleepRead {
            reads += from to to
            failure?.let { throw it }
            return SleepRead(sessions.filter { it.end >= from && it.start <= to }, hr = hr, complete = complete)
        }
    }
    private val store = object : SleepSyncStore {
        var last: LocalDate? = null
        override suspend fun sleepLastRead() = last
        override suspend fun setSleepLastRead(date: LocalDate) { last = date }
    }
    private val repo = SleepRepository(t.db, source, store, now = { now }, zone = { est })

    @After fun tearDown() = t.close()

    /** 22:10 the evening before [wake] to 06:50: light, deep, a 40-minute wake, REM, then awake until up. */
    private fun night(wake: String, id: String = "s-$wake", stagesUp: Boolean = true): Session {
        val start = LocalDate.parse(wake).minusDays(1).atTime(22, 10).toInstant(est)
        fun m(min: Long) = start.plusSeconds(min * 60)
        val stages = listOf(Stage(m(0), m(120), 4), Stage(m(120), m(240), 5), Stage(m(240), m(280), 1), Stage(m(280), m(485), 6), Stage(m(485), m(520), 1))
        return Session(id, oura, start, m(520), est, est, if (stagesUp) stages else emptyList())
    }

    private suspend fun rows() = t.db.sleepDao().range("2000-01-01", "2100-01-01")

    @Test fun storesANightWithItsStagesAndSamples() = runTest {
        source.sessions = listOf(night("2026-11-26"))
        source.hr = listOf(Sample(oura, at("2026-11-25T23:00"), 58.0), Sample(oura, at("2026-11-26T02:00"), 50.0))
        val r = repo.sync()
        assertEquals(1, r.added)
        val row = rows().single()
        assertEquals("2026-11-26", row.date)
        assertEquals("main", row.type)
        assertEquals("2026-11-25 22:10", row.bedStart)
        assertEquals("2026-11-26 06:15", row.wokeAt)
        assertEquals("2026-11-26 06:50", row.bedEnd)
        assertEquals(485 * 60L, row.timeInBedS)
        assertEquals(445 * 60L, row.totalSleepS)
        assertEquals(1, row.awakenings)
        assertEquals(50.0, row.lowestHr!!, 0.0)
        assertEquals(5, SleepJson.parseStages(row.stages).size)
        assertEquals(2, SleepJson.parsePoints(row.hrSamples).size)
        assertNull(row.avgSpo2)
    }

    @Test fun firstReadGoesBack30DaysAndLaterReadsTwoDaysBeforeTheLast() = runTest {
        source.sessions = listOf(night("2026-11-26"))
        repo.sync()
        assertEquals(LocalDate.parse("2026-10-26").atStartOfDay(est).toInstant(), source.reads.last().first)
        now = at("2026-11-28T08:00")
        repo.sync()
        // Last read the 26th: from the 24th, starting the evening before.
        assertEquals(LocalDate.parse("2026-11-23").atStartOfDay(est).toInstant(), source.reads.last().first)
    }

    @Test fun aLateSyncUpdatesTheNightInPlace() = runTest {
        source.sessions = listOf(night("2026-11-26", stagesUp = false))
        repo.sync()
        assertNull(rows().single().totalSleepS)
        now = at("2026-11-26T10:00")
        source.sessions = listOf(night("2026-11-26"))
        val r = repo.sync()
        assertEquals(1, r.updated)
        assertEquals(445 * 60L, rows().single().totalSleepS)
    }

    @Test fun aNightIsFrozenThreeDaysAfterItsWakeDate() = runTest {
        source.sessions = listOf(night("2026-11-20", stagesUp = false))
        now = at("2026-11-21T08:00")
        repo.sync()
        // Four days later the source fills in stages; the stored night stays as it was.
        now = at("2026-11-24T08:00")
        source.sessions = listOf(night("2026-11-20"))
        val r = repo.sync()
        assertEquals(0, r.updated)
        assertNull(rows().single().totalSleepS)
    }

    @Test fun aNightTheSourceDroppedIsRemovedWhileItCanStillChange() = runTest {
        source.sessions = listOf(night("2026-11-25", id = "a"), night("2026-11-26", id = "b"))
        repo.sync()
        source.sessions = listOf(night("2026-11-26", id = "b"))
        val r = repo.sync()
        assertEquals(1, r.removed)
        assertEquals(listOf("b"), rows().map { it.sourceId })
    }

    @Test fun aNightTheRingSendsDaysLateIsStillStored() = runTest {
        source.sessions = listOf(night("2026-11-20"))
        now = at("2026-11-20T08:00")
        repo.sync()
        // Read every hour for days with nothing new, then the ring catches up all at once.
        for (d in 21..26) { now = at("2026-11-${d}T08:00"); repo.sync() }
        source.sessions = (20..26).map { night("2026-11-$it") }
        val r = repo.sync()
        assertEquals(6, r.added)
        assertEquals(7, rows().size)
    }

    @Test fun aCappedReadRemovesNothing() = runTest {
        source.sessions = listOf(night("2026-11-25", id = "a"), night("2026-11-26", id = "b"))
        repo.sync()
        source.sessions = listOf(night("2026-11-26", id = "b"))
        source.complete = false
        val r = repo.sync()
        assertEquals(0, r.removed)
        assertEquals(2, rows().size)
    }

    @Test fun aFailedReadWritesNothingAndKeepsTheLastRead() = runTest {
        source.sessions = listOf(night("2026-11-26"))
        repo.sync()
        val before = store.last
        now = at("2026-11-27T08:00")
        source.failure = SecurityException("no permission")
        try { repo.sync(); fail("should throw") } catch (_: SecurityException) {}
        assertEquals(before, store.last)
        assertEquals(1, rows().size)
    }

    @Test fun readingTheSameNightTwiceKeepsOneRow() = runTest {
        source.sessions = listOf(night("2026-11-26"))
        repo.sync()
        now = at("2026-11-26T09:00")
        repo.sync()
        assertEquals(1, rows().size)
        assertTrue(rows().all { it.source == "health_connect" && it.origin == oura })
    }
}
