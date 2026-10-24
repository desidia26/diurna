package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.domain.Metrics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetricsRepositoryTest {
    private val t = TestDb()
    private val mon = t.mon
    private val lastWeek = mon.minusDays(7)

    @After fun tearDown() = t.close()

    @Test fun sessionsComeOutOldestFirstInLoggedOrder() = runTest {
        val push = t.template("Push A", "Bench press", "Dips")
        t.days.startWorkout(lastWeek, push)
        t.fill(lastWeek, "Bench press", 175.0, 5, 5, 5)
        t.days.startWorkout(mon, push)
        t.fill(mon, "Bench press", 180.0, 5, 4)
        t.fill(mon, "Dips", 0.0, 10)

        val sessions = t.metrics.sessions.first()
        assertEquals(listOf(lastWeek, mon, mon), sessions.map { it.date })
        assertEquals(listOf("Bench press", "Bench press", "Dips"), sessions.map { it.name })
        assertEquals(180.0, sessions[1].topWeight!!, 1e-9)
        assertEquals(listOf(5, 4), sessions[1].sets.mapNotNull { it.reps })
        assertEquals("Dips last week had no reps, so it isn't a session", 3, sessions.size)
    }

    @Test fun deletedSetsDropOut() = runTest {
        val push = t.template("Push A", "Bench press")
        t.days.startWorkout(mon, push)
        t.fill(mon, "Bench press", 180.0, 5, 5, 5)
        val ex = t.days.observeWorkout(mon).first()!!.exercises.single()
        t.days.deleteSet(ex.sets.maxBy { it.position }.id, ex.logged.id)
        assertEquals(1800.0, t.metrics.sessions.first().single().volume, 1e-9)
    }

    @Test fun todaysUnfinalizedWeighInIsIncludedAndAveragesReachBack() = runTest {
        t.nutrition.setBodyWeight(mon.minusDays(40), 190.0) // before the range and its 6-day lead-in
        t.nutrition.setBodyWeight(mon.minusDays(28), 186.0)
        t.nutrition.setBodyWeight(mon, 183.0)
        val s = t.metrics.observe(Metrics.Range.W4, mon).first()
        assertEquals(mon.minusDays(27), s.from)
        assertEquals("rows start 6 days before the range", listOf(mon.minusDays(28), mon), s.days.map { java.time.LocalDate.parse(it.date) })
        assertEquals(183.0, s.days.last().bodyWeight!!, 1e-9)
        assertEquals(mon.minusDays(40), t.metrics.observe(Metrics.Range.ALL, mon).first().from)
    }
}
