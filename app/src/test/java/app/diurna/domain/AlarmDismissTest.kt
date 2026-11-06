package app.diurna.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AlarmDismissTest {
    @Test fun onlyExactPorchSecretStopsTheAlarm() {
        assertTrue(AlarmDismiss.matchesPorch("daybook:porch:abc", "daybook:porch:abc"))
        assertFalse(AlarmDismiss.matchesPorch("daybook:porch:ABC", "daybook:porch:abc"))
        assertFalse(AlarmDismiss.matchesPorch("other", "daybook:porch:abc"))
        assertFalse(AlarmDismiss.matchesPorch("", "daybook:porch:abc"))
    }

    @Test fun morningIsRecordedOnlyForRealDatedAlarms() {
        val day = LocalDate.of(2026, 11, 5)
        assertTrue(AlarmDismiss.recordsMorning(test = false, date = day))
        assertFalse(AlarmDismiss.recordsMorning(test = true, date = day))
        assertFalse(AlarmDismiss.recordsMorning(test = false, date = null))
        assertFalse(AlarmDismiss.recordsMorning(test = true, date = null))
    }
}
