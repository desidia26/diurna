package app.daybook.domain

import app.daybook.domain.AlarmRules.Morning
import app.daybook.domain.AlarmRules.MorningResult
import app.daybook.ui.servingsLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class DayTextTest {
    private fun m(status: Morning, late: Int? = null) = MorningResult(status, late)

    @Test fun everyMorningStatusReadsSensibly() {
        assertEquals("Up at 06:04 · on time", DayText.morning(m(Morning.ON_TIME, 4), "06:00", "06:04"))
        assertEquals("Up at 06:22 · 22 min late", DayText.morning(m(Morning.LATE, 22), "06:00", "06:22"))
        assertEquals("No alarm today", DayText.morning(m(Morning.OFF), "off", null))
        assertEquals("Alarm 06:00 · not scanned yet", DayText.morning(m(Morning.WAITING), "06:00", null))
        assertEquals("Missed the 06:00 alarm", DayText.morning(m(Morning.MISSED), "06:00", null))
        assertEquals("Alarm at 06:00", DayText.morning(m(Morning.UPCOMING), "06:00", null))
        assertEquals("Alarm at 06:00", DayText.morning(m(Morning.PLANNED), "06:00", null))
    }

    @Test fun workoutAndFoodLines() {
        assertEquals("Push A · 2 of 4 exercises", DayText.workout("Push A", 2, 4))
        assertEquals("Push A planned", DayText.plannedWorkout("Push A"))
        assertEquals("Rest day", DayText.plannedWorkout(null))
        assertEquals("No food logged yet", DayText.food(emptyList()))
        assertEquals("1 food entry · 08:15", DayText.food(listOf("08:15")))
        assertEquals("3 food entries · last 19:05", DayText.food(listOf("08:15", "19:05", "12:30")))
    }

    @Test fun servingsLabels() {
        assertEquals("½", 0.5.servingsLabel())
        assertEquals("1", 1.0.servingsLabel())
        assertEquals("1½", 1.5.servingsLabel())
        assertEquals("2", 2.0.servingsLabel())
        assertEquals("2.5", 2.5.servingsLabel())
    }
}
