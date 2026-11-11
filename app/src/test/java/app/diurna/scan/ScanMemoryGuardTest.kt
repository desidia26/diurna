package app.diurna.scan

import app.diurna.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ScanMemoryGuardTest {
    @Test fun injectedAllocationFailureIsSafeAndKeepsLiveConfirmation() = runTest {
        val s = LiveScanSession()
        val code = ProductCode("4006381333931")
        s.observe(listOf(code)); s.beginCapture()
        val result = guardScanMemory { throw OutOfMemoryError("injected, no large allocation") }
        assertEquals(ScanOutcome.Unreadable, result)
        assertEquals(ScanOutcome.Confirm(listOf(code)), s.complete(result)!!.outcome)
        assertNull(s.complete(result))
    }
    @Test fun ocrAllocationFailureDoesNotPretendRecognitionSucceeded() = runTest {
        val image = ScanPixels(1, 1, intArrayOf(-1))
        val result = guardScanMemory {
            decodeProductImage(image, { _, _ -> emptyList() }, { throw OutOfMemoryError("injected OCR") })
        }
        assertEquals(ScanOutcome.Unreadable, result)
    }
    @Test fun cancellationIsNotSwallowed() = runTest {
        try { guardScanMemory { throw CancellationException("cancel") }; fail("cancel must propagate") }
        catch (_: CancellationException) { }
    }
}
