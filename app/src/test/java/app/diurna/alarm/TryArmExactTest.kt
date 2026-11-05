package app.diurna.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TryArmExactTest {
    @Test fun successReturnsTrue() {
        var ran = false
        assertTrue(tryArmExact { ran = true })
        assertTrue(ran)
    }

    @Test fun missingExactAlarmPermissionReturnsFalse() {
        assertFalse(tryArmExact { throw SecurityException("exact alarms not permitted") })
    }

    @Test fun otherFailuresPropagate() {
        try {
            tryArmExact { error("boom") }
            throw AssertionError("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("boom"))
        }
    }
}
