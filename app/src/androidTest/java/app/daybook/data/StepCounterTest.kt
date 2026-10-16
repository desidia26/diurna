package app.daybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.daybook.domain.StepMath
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class StepCounterTest {
    private val t = TestDb()
    private val mon = t.mon

    /** Fake sensor: whatever total we set; null means no sensor or no permission. */
    private var reading: Long? = null
    private var booted: LocalDateTime = mon.minusDays(5).atTime(8, 0)
    private val sensor = object : CounterSensor { override suspend fun read() = reading }
    private val store = object : StepStateStore {
        var state: StepMath.State? = null
        override suspend fun loadStepState() = state
        override suspend fun saveStepState(state: StepMath.State) { this.state = state }
    }
    private val counter = StepCounter(t.db, sensor, store, now = { t.now }, bootedAt = { booted })

    @After fun tearDown() = t.close()

    private suspend fun steps(d: java.time.LocalDate) = t.db.dayDao().get(d.toString())?.steps

    @Test fun firstLookStartsTodayAtZeroThenCounts() = runTest {
        t.now = mon.atTime(8, 0); reading = 20_000
        counter.sample()
        assertEquals("baseline only", 0L, steps(mon))
        t.now = mon.atTime(8, 15); reading = 20_640
        counter.sample()
        t.now = mon.atTime(8, 30); reading = 21_000
        counter.sample()
        assertEquals(1_000L, steps(mon))
    }

    @Test fun midnightLookKeepsEveningStepsOnTheirDay() = runTest {
        t.now = mon.atTime(22, 0); reading = 5_000; counter.sample()
        t.now = mon.atTime(23, 59); reading = 5_800; counter.sample()
        t.now = mon.plusDays(1).atTime(7, 30); reading = 6_100; counter.sample()
        assertEquals(800L, steps(mon))
        assertEquals(300L, steps(mon.plusDays(1)))
    }

    @Test fun rebootDoesNotLoseTheDayOrGoNegative() = runTest {
        t.now = mon.atTime(9, 0); reading = 30_000; counter.sample()
        t.now = mon.atTime(12, 0); reading = 32_000; counter.sample()
        booted = mon.atTime(12, 30) // phone restarted, sensor back to 0
        t.now = mon.atTime(13, 0); reading = 450; counter.sample()
        assertEquals(2_450L, steps(mon))
    }

    @Test fun noSensorOrPermissionChangesNothing() = runTest {
        reading = null
        counter.sample()
        assertNull(steps(mon))
        assertNull(store.state)
    }

    @Test fun readingTodayTakesAFreshLookButPastDaysDont() = runTest {
        t.now = mon.atTime(9, 0); reading = 1_000; counter.sample()
        t.now = mon.atTime(9, 10); reading = 1_250
        assertEquals(250L, counter.stepsOn(mon))
        t.now = mon.plusDays(1).atTime(9, 0); reading = 9_999
        assertEquals("yesterday isn't re-sampled", 250L, counter.stepsOn(mon))
    }

    @Test fun finishedDayFreezesItsCount() = runTest {
        val finalizer = DayFinalizer(t.db, t.days, t.nutrition, counter) { t.now }
        t.now = mon.atTime(9, 0); reading = 100; counter.sample()
        t.now = mon.atTime(20, 0); reading = 10_400; counter.sample()
        t.now = mon.plusDays(1).atTime(8, 0); reading = 10_500
        finalizer.catchUp()
        assertEquals(10_300L, steps(mon))
        assertEquals(true, t.db.dayDao().get(mon.toString())!!.finalized)
    }
}
