package app.diurna.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class AlarmScheduleDecisionTest {
    private val next = AlarmRules.NextAlarm(LocalDate.of(2026, 11, 6), LocalTime.of(6, 30))

    @Test fun needsPorchCodeWinsEvenWhenANextAlarmExists() {
        val state = AlarmScheduleDecision.afterReschedule(
            porchConfirmed = false, next = next, armSucceeded = true,
        )
        assertEquals(ArmedState.NeedsPorchCode, state)
        assertTrue(AlarmScheduleDecision.shouldCancelExisting(state))
    }

    @Test fun noneUpcomingWhenNothingInLookahead() {
        val state = AlarmScheduleDecision.afterReschedule(
            porchConfirmed = true, next = null, armSucceeded = false,
        )
        assertEquals(ArmedState.NoneUpcoming, state)
        assertTrue(AlarmScheduleDecision.shouldCancelExisting(state))
    }

    @Test fun armedWhenSystemAcceptedExactAlarm() {
        val state = AlarmScheduleDecision.afterReschedule(
            porchConfirmed = true, next = next, armSucceeded = true,
        )
        assertEquals(ArmedState.Armed(next), state)
        assertFalse(AlarmScheduleDecision.shouldCancelExisting(state))
    }

    @Test fun notAllowedWhenExactAlarmPermissionMissing() {
        val state = AlarmScheduleDecision.afterReschedule(
            porchConfirmed = true, next = next, armSucceeded = false,
        )
        assertEquals(ArmedState.NotAllowed, state)
        // Leave any previously armed PendingIntent alone; the Setup checklist must grant permission.
        assertFalse(AlarmScheduleDecision.shouldCancelExisting(state))
    }
}
