package app.daybook.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.daybook.domain.Activity
import app.daybook.domain.AlarmRules
import app.daybook.domain.Goal
import app.daybook.domain.Profile
import app.daybook.domain.Sex
import app.daybook.domain.TargetMath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DayFinalizerTest {
    private val t = TestDb()
    private val mon = t.mon
    private val dao = t.db.dayDao()
    private val me = Profile(Sex.MALE, LocalDate.of(1996, 1, 15), 70.0, Activity.MODERATE, Goal.CUT)

    @After fun tearDown() = t.close()

    private suspend fun day(d: LocalDate) = dao.get(d.toString())

    @Test fun snapshotsEveryPastDayIncludingEmptyOnes() = runTest {
        t.food.add(mon, "eggs") // first day ever
        t.now = mon.plusDays(4).atTime(9, 0) // Friday; Tue–Thu never opened
        t.finalizer.catchUp()
        for (i in 0L..3L) {
            val d = day(mon.plusDays(i))!!
            assertTrue("day $i finalized", d.finalized)
            assertEquals(AppDatabase.DEFAULT_ALARM, d.scheduledAlarm)
        }
        assertNull("today isn't over yet", day(mon.plusDays(4)))
    }

    @Test fun routineEditsLeaveFinishedDaysAlone() = runTest {
        val push = t.template("Push A", "Bench press")
        t.routine.setTemplate(mon.dayOfWeek.value, push)
        t.days.recordMorning(mon, "06:20", "scan")
        t.now = mon.plusDays(1).atTime(9, 0)

        // Both edits finalize Monday before they apply.
        t.routine.setAlarm(mon.dayOfWeek.value, "08:00")
        t.routine.setTemplate(mon.dayOfWeek.value, null)

        val d = day(mon)!!
        assertEquals("06:30", d.scheduledAlarm)
        assertEquals("Push A", d.plannedWorkout)
        assertEquals("06:30", t.days.effectiveAlarm(mon))
        assertEquals("next Monday follows the new routine", "08:00", t.days.effectiveAlarm(mon.plusDays(7)))
    }

    @Test fun offDayStaysOff() = runTest {
        t.days.setAlarmOverride(mon.plusDays(1), AlarmRules.OFF) // set Monday noon, before the lock
        t.now = mon.plusDays(2).atTime(9, 0)
        t.finalizer.catchUp()
        assertEquals(AlarmRules.OFF, day(mon.plusDays(1))!!.scheduledAlarm)
    }

    @Test fun targetsAreFrozenWithTheDay() = runTest {
        t.profile.value = me
        t.nutrition.setBodyWeight(mon, 180.0)
        val live = t.nutrition.observeTargets(mon).first()
        assertEquals(TargetMath.targets(me, 180.0, mon), live)

        t.now = mon.plusDays(1).atTime(9, 0)
        t.finalizer.catchUp()
        t.profile.value = me.copy(goal = Goal.LEAN_BULK) // later change of plan
        assertEquals("Monday keeps the cut targets", live, t.nutrition.observeTargets(mon).first())
        assertEquals(TargetMath.targets(me.copy(goal = Goal.LEAN_BULK), 180.0, mon.plusDays(1)), t.nutrition.observeTargets(mon.plusDays(1)).first())
    }

    @Test fun targetsUseLatestWeighInOnOrBeforeTheDay() = runTest {
        t.profile.value = me
        t.nutrition.setBodyWeight(mon, 180.0)
        t.nutrition.setBodyWeight(mon.plusDays(3), 177.0)
        assertEquals(TargetMath.targets(me, 180.0, mon.plusDays(2)), t.nutrition.observeTargets(mon.plusDays(2)).first())
        assertEquals(TargetMath.targets(me, 177.0, mon.plusDays(5)), t.nutrition.observeTargets(mon.plusDays(5)).first())
        assertNull("no weigh-in yet before Monday", t.nutrition.observeTargets(mon.minusDays(1)).first())
    }

    @Test fun noTargetsWithoutAProfile() = runTest {
        t.nutrition.setBodyWeight(mon, 180.0)
        assertNull(t.nutrition.observeTargets(mon).first())
    }

    @Test fun backfilledOlderDayGetsFinalizedToo() = runTest {
        t.food.add(mon, "eggs")
        t.now = mon.plusDays(3).atTime(9, 0)
        t.finalizer.catchUp()
        t.food.add(mon.minusDays(5), "pizza") // older than anything finalized
        assertFalse(day(mon.minusDays(5))!!.finalized)
        t.finalizer.catchUp()
        assertTrue(day(mon.minusDays(5))!!.finalized)
    }

    @Test fun catchUpIsIdempotent() = runTest {
        t.food.add(mon, "eggs")
        t.now = mon.plusDays(2).atTime(9, 0)
        t.finalizer.catchUp()
        val first = day(mon)
        t.finalizer.catchUp()
        assertEquals(first, day(mon))
    }

    @Test fun stepsAreFrozenWithTheDay() = runTest {
        t.food.add(mon, "eggs")
        t.stepCounts[mon] = 8_420
        t.now = mon.plusDays(1).atTime(9, 0)
        t.finalizer.catchUp()
        assertEquals(8_420L, day(mon)!!.steps)
        t.stepCounts[mon] = 1 // Health Connect changing later doesn't touch the frozen count
        t.finalizer.catchUp()
        assertEquals(8_420L, day(mon)!!.steps)
    }

    @Test fun stepsAreFilledInForDaysFinalizedWithoutThem() = runTest {
        t.food.add(mon, "eggs")
        t.now = mon.plusDays(2).atTime(9, 0)
        t.finalizer.catchUp() // no step data yet, e.g. before Health Connect was connected
        assertNull(day(mon)!!.steps)
        t.stepCounts[mon] = 11_002
        t.finalizer.catchUp()
        assertEquals(11_002L, day(mon)!!.steps)
    }
}
