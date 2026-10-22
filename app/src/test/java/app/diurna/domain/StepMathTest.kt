package app.diurna.domain

import app.diurna.domain.StepMath.State
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StepMathTest {
    private val day = LocalDate.of(2026, 10, 16)
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m)
    private val bootedLongAgo = day.minusDays(3).atTime(8, 0)

    @Test fun firstLookOnlySetsTheBaseline() {
        val r = StepMath.look(null, 12_345, at(9), bootedLongAgo)
        assertEquals(0, r.add)
        assertEquals(State(12_345, at(9)), r.next)
    }

    @Test fun normalLookAddsTheDifferenceToToday() {
        val r = StepMath.look(State(12_000, at(9)), 12_850, at(9, 15), bootedLongAgo)
        assertEquals(850, r.add)
        assertEquals(day, r.date)
    }

    @Test fun noMovementAddsNothing() {
        assertEquals(0, StepMath.look(State(500, at(9)), 500, at(9, 15), bootedLongAgo).add)
    }

    @Test fun rebootDetectedBecauseTheCounterWentDown() {
        // Sensor restarted from 0 and has counted 300 since.
        val r = StepMath.look(State(15_000, at(9)), 300, at(10), bootedAt = null)
        assertEquals(300, r.add)
    }

    @Test fun rebootDetectedFromBootTimeEvenIfCounterIsHigher() {
        // Rebooted at 9:30 after the last look, then walked 16,000 steps: counter is above the old value.
        val r = StepMath.look(State(15_000, at(9)), 16_000, at(18), bootedAt = at(9, 30))
        assertEquals("all counts since the reboot are new", 16_000, r.add)
    }

    @Test fun stepsGoToTheDayOfTheLook() {
        // Last look 23:59 yesterday, next at 7:00 today: overnight steps go to today.
        val r = StepMath.look(State(10_000, day.minusDays(1).atTime(23, 59)), 10_400, at(7), bootedLongAgo)
        assertEquals(day, r.date)
        assertEquals(400, r.add)
    }

    @Test fun glitchesAreIgnoredButTheBaselineMovesOn() {
        val r = StepMath.look(State(1_000, at(9)), 1_000 + StepMath.MAX_PLAUSIBLE + 1, at(9, 15), bootedLongAgo)
        assertEquals(0, r.add)
        assertEquals("next look counts from the new value", 1_000 + StepMath.MAX_PLAUSIBLE + 1, r.next.counter)
    }

    @Test fun negativeReadingIsIgnored() {
        assertEquals(0, StepMath.look(State(1_000, at(9)), -5, at(9, 15), bootedLongAgo).add)
    }
}
