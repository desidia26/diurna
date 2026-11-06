package app.diurna.scan

import app.diurna.debug.*
import app.diurna.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DebugScanDiagnosticsTest {
    @Test fun mapsEventsAndAttachmentsWithOneCorrelation() {
        val events = mutableListOf<Action.Event>()
        val kinds = mutableListOf<AttachmentKind>()
        val d = DebugScanDiagnostics({ true }, Screen.LIBRARY, { events += it },
            { _, kind, _ -> kinds += kind }, { _, kind, _ -> kinds += kind })
        d.onEvent("camera_bind", "failed", mapOf("exception" to "IllegalStateException"))
        d.onAttempt(ScanVariant.CROP, "no_code", 42)
        d.onCaptured(File("unused"))
        d.onResult("barcode", listOf(ProductCode("4006381333931")))
        assertEquals(Action.Result.FAILED, events.first().result)
        assertEquals("scan.decode.crop.no_code", events[1].name)
        assertEquals(42L, events[1].latencyMs)
        assertEquals(1, events.map { it.correlationId }.distinct().size)
        assertTrue(events.all { it.screen == Screen.LIBRARY })
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.BARCODE), kinds)
    }
    @Test fun disabledHasNoCallbacksEvenAfterToggleOff() {
        var enabled = false
        var calls = 0
        val d = DebugScanDiagnostics({ enabled }, Screen.DAY, { calls++ },
            { _, _, _ -> calls++ }, { _, _, _ -> calls++ })
        d.onCaptured(File("does-not-exist")); d.onResult("barcode", listOf(ProductCode("4006381333931")))
        d.onEvent("permission", "denied"); d.onAttempt(ScanVariant.ORIGINAL, "no_code", 0)
        assertEquals(0, calls)
        enabled = true; d.onEvent("camera_bind", "ok"); enabled = false
        d.onCaptured(File("does-not-exist")); assertEquals(1, calls)
    }
}
