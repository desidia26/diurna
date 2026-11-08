package app.diurna.scan

import app.diurna.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class LiveScanSessionTest {
    private val a = ProductCode("4006381333931")
    private val b = ProductCode("036000291452")
    @Test fun singleLiveReadSurvivesUnreadableStillButRequiresConfirmation() {
        val s = LiveScanSession(); assertTrue(s.observe(listOf(a)))
        assertTrue(s.beginCapture()); val r = s.complete(ScanOutcome.Unreadable)!!
        assertEquals(ScanOutcome.Confirm(listOf(a)), r.outcome); assertEquals("live_confirmation", r.source)
        assertNull(s.complete(ScanOutcome.Unreadable)); assertFalse(s.beginCapture())
    }
    @Test fun matchingLiveAndStillCanDecodeButConflictsRequireChoice() {
        val s = LiveScanSession(); s.observe(listOf(a)); s.observe(listOf(a)); s.beginCapture()
        assertEquals(ScanOutcome.Decoded(a), s.complete(ScanOutcome.Decoded(a))!!.outcome)
        val c = LiveScanSession(); c.observe(listOf(a,b)); c.beginCapture()
        assertEquals(setOf(a,b), (c.complete(ScanOutcome.Decoded(a))!!.outcome as ScanOutcome.Confirm).codes.toSet())
    }
    @Test fun invalidCodesIgnoredAndLeadingZeroAliasesDeduplicated() {
        val s = LiveScanSession()
        assertFalse(s.observe(listOf(ProductCode("4006381333932"))))
        s.observe(listOf(b,ProductCode("0036000291452"))); s.beginCapture()
        assertEquals(1,(s.complete(ScanOutcome.Unreadable)!!.outcome as ScanOutcome.Confirm).codes.size)
    }
    @Test fun staleCandidatesExpireBeforeCaptureButFreezeDuringDecode() {
        var now = 0L; val s = LiveScanSession { now }
        s.observe(listOf(a)); now = 6000; s.beginCapture()
        assertEquals(ScanOutcome.Unreadable,s.complete(ScanOutcome.Unreadable)!!.outcome)
        val fresh = LiveScanSession { now }; fresh.observe(listOf(b)); fresh.beginCapture(); now += 30000
        assertEquals(ScanOutcome.Confirm(listOf(b)),fresh.complete(ScanOutcome.TextModelNotReady)!!.outcome)
    }
    @Test fun cancelledCallbacksCannotFinishOrPoisonRetrySession() {
        val old = LiveScanSession(); old.observe(listOf(a)); old.beginCapture(); old.cancel()
        assertFalse(old.observe(listOf(b))); assertNull(old.complete(ScanOutcome.Decoded(a)))
        val retry = LiveScanSession(); retry.beginCapture()
        assertEquals(ScanOutcome.Unreadable,retry.complete(ScanOutcome.Unreadable)!!.outcome)
    }
    @Test fun captureAndCompletionAreExactlyOnceUnderConcurrency() {
        val s = LiveScanSession(); s.observe(listOf(a)); val pool = Executors.newFixedThreadPool(8)
        try {
            assertEquals(1,pool.invokeAll(List(32) { java.util.concurrent.Callable { s.beginCapture() } }).count { it.get() })
            assertEquals(1,pool.invokeAll(List(32) { java.util.concurrent.Callable { s.complete(ScanOutcome.Unreadable) } }).count { it.get()!=null })
        } finally { pool.shutdown() }
    }
    @Test fun candidateLimitDoesNotEvictRetainedLiveRead() {
        val s = LiveScanSession(); s.observe(listOf(a)); s.beginCapture()
        val result = s.complete(ScanOutcome.Confirm(listOf(b)))!!
        assertEquals("combined_confirmation", result.source)
        assertTrue((result.outcome as ScanOutcome.Confirm).codes.contains(a))
    }
    @Test fun lateDetectionsCannotChangeCapturedCandidateSnapshot() {
        val s = LiveScanSession(); s.observe(listOf(a)); s.beginCapture(); assertFalse(s.observe(listOf(b)))
        assertEquals(ScanOutcome.Confirm(listOf(a)),s.complete(ScanOutcome.Unreadable)!!.outcome)
    }
    @Test fun invalidStillCannotPromoteLiveOnlyCandidateToAutomaticLookup() {
        val s = LiveScanSession(); s.observe(listOf(a)); s.beginCapture()
        assertEquals(ScanOutcome.Confirm(listOf(a)), s.complete(ScanOutcome.Decoded(ProductCode("4006381333932")))!!.outcome)
    }
}
