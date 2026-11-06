package app.diurna.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for 2b94287: a camera that won't start must not crash (which would take the
 * ringing AlarmService down with the alarm UI). tryStartCamera turns any Exception into a soft miss.
 */
class CameraStartTest {
    @Test fun successPassesThrough() {
        val result = tryStartCamera { "camera" }
        assertTrue(result.isSuccess)
        assertEquals("camera", result.getOrNull())
    }

    @Test fun runtimeFailureIsSoftMiss() {
        val result = tryStartCamera<Unit> { error("no camera") }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test fun checkedStyleFailureIsSoftMiss() {
        val result = tryStartCamera<Unit> { throw Exception("ProcessCameraProvider failed") }
        assertTrue(result.isFailure)
        assertEquals("ProcessCameraProvider failed", result.exceptionOrNull()!!.message)
    }

    @Test fun errorDoesNotEscape() {
        // The alarm screen's listener must never let this throw on the main thread.
        val result = tryStartCamera<Nothing> { throw RuntimeException("bindToLifecycle") }
        assertTrue(result.isFailure)
    }
}
