package app.diurna.ui.web

import org.junit.Assert.*
import org.junit.Test

class SvelteWeekGateTest {
    @Test fun inactiveUnlessDebugAndPreferenceAreBothOn() {
        assertFalse(SvelteWeekGate.active(debugEnabled = false, preferenceEnabled = false))
        assertFalse(SvelteWeekGate.active(debugEnabled = false, preferenceEnabled = true))
        assertFalse(SvelteWeekGate.active(debugEnabled = true, preferenceEnabled = false))
        assertTrue(SvelteWeekGate.active(debugEnabled = true, preferenceEnabled = true))
    }

    @Test fun toggleIsHiddenWhenDebugIsOff() {
        assertFalse(SvelteWeekGate.toggleVisible(debugEnabled = false))
        assertTrue(SvelteWeekGate.toggleVisible(debugEnabled = true))
    }
}
