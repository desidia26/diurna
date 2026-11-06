package app.diurna.scan

import app.diurna.debug.*
import app.diurna.domain.*
import java.io.File
import java.util.UUID

/** One correlation per dialog. Always rechecks opt-in, including on camera worker callbacks. */
class DebugScanDiagnostics(
    private val isEnabled: () -> Boolean,
    private val screen: Screen,
    private val dispatch: (Action.Event) -> Unit,
    private val image: (File, AttachmentKind, String) -> Unit,
    private val barcode: (ByteArray, AttachmentKind, String) -> Unit,
    private val variant: (ScanPixels, String) -> Unit = { _, _ -> },
) : ScanDiagnostics {
    override val enabled: Boolean get() = isEnabled()
    override fun onFailedVariant(pixels: ScanPixels) {
        if (enabled) runCatching { variant(pixels, correlation) }
    }
    private val correlation = UUID.randomUUID().toString()
    override fun onEvent(name: String, outcome: String, details: Map<String, String>, latencyMs: Long?) {
        if (!enabled) return
        runCatching {
            val result = when (outcome) {
                "start" -> Action.Result.STARTED
                "ok", "decoded", "ready", "granted", "confirm", "known", "found", "summary", "candidate" -> Action.Result.OK
                "cancelled" -> Action.Result.CANCELLED
                else -> Action.Result.FAILED
            }
            dispatch(Action.Event(screen, "scan.$name.$outcome", correlation, result, latencyMs, details))
        }
    }
    override fun onAttempt(variant: ScanVariant, result: String) = onAttempt(variant, result, null)
    override fun onAttempt(variant: ScanVariant, result: String, latencyMs: Long?) =
        onEvent("decode.${variant.name.lowercase()}", result, latencyMs = latencyMs)
    override fun onCaptured(file: File) {
        if (!enabled) return
        runCatching { image(file, AttachmentKind.IMAGE, correlation) }
        onEvent("capture", "ok")
    }
    override fun onResolved(resolution: ScanResolution) {
        if (!enabled) return
        val codes = when (val result = resolution.outcome) {
            is ScanOutcome.Decoded -> listOf(result.code)
            is ScanOutcome.Confirm -> result.codes
            else -> emptyList()
        }
        codes.forEach { code -> runCatching { barcode(code.digits.toByteArray(), AttachmentKind.BARCODE, correlation) } }
        onEvent("outcome", when (resolution.outcome) {
            is ScanOutcome.Decoded -> "decoded"; is ScanOutcome.Confirm -> "confirm"
            ScanOutcome.Unreadable -> "unreadable"; ScanOutcome.TextModelNotReady -> "ocr_model_not_ready"
        }, mapOf("source" to resolution.source, "candidates" to codes.size.toString()))
    }
    override fun onResult(result: String, codes: List<ProductCode>) {
        if (!enabled) return
        codes.forEach { code -> runCatching { barcode(code.digits.toByteArray(), AttachmentKind.BARCODE, correlation) } }
        onEvent("image_outcome", when (result) { "barcode" -> "decoded"; "ocr_confirm" -> "confirm"; else -> result },
            mapOf("candidates" to codes.size.toString()))
    }
}
