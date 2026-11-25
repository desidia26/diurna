package app.diurna.ui

import app.diurna.debug.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupPagesTest {
    @Test fun everyPageMakesAValidDebugEvent() {
        // Action.Event rejects bad names by throwing, which crashed opening a nested page.
        (SetupPages + null).forEach { setupPageEvent(it) }
        assertEquals("setup.page.food.targets", setupPageEvent("food/targets").name)
        assertEquals("setup.page.main", setupPageEvent(null).name)
    }

    @Test fun mealsAndExercisesCountAsLibrary() {
        assertEquals(Screen.LIBRARY, setupPageEvent("food/meals").screen)
        assertEquals(Screen.LIBRARY, setupPageEvent("training/exercises").screen)
        assertEquals(Screen.SETUP, setupPageEvent("food").screen)
    }
}
