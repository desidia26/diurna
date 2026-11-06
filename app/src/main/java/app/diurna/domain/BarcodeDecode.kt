package app.diurna.domain

import app.diurna.data.expandUpcE
import app.diurna.data.normalizeBarcode
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlin.math.min
import kotlin.math.sqrt

/** Only product codes with a correct GS1 modulo-10 check digit can leave the scanner. */
data class ProductCode(val digits: String, val upcE: Boolean = false) {
    fun isValid(): Boolean {
        if (!digits.all { it in '0'..'9' }) return false
        val full = if (upcE) {
            if (digits.length != 8 || digits[0] !in "01") return false
            expandUpcE(digits) ?: return false
        } else digits
        if (full.length !in listOf(8, 12, 13)) return false
        val sum = full.dropLast(1).reversed().mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10 == full.last() - '0'
    }
    val key: String? get() = normalizeBarcode(digits, upcE)
}

enum class ScanVariant { ORIGINAL, CROP, ROTATE, CONTRAST, THRESHOLD, SHARPEN }

/** Scanner never owns debug storage: an opt-in recorder may copy the file during this callback. */
interface ScanDiagnostics {
    fun onResolved(resolution: ScanResolution) {}
    fun onEvent(name: String, outcome: String, details: Map<String, String> = emptyMap(), latencyMs: Long? = null) {}
    val enabled: Boolean get() = false
    fun onFailedVariant(pixels: ScanPixels) {}
    fun onCaptured(file: File) {}
    fun onAttempt(variant: ScanVariant, result: String) {}
    fun onAttempt(variant: ScanVariant, result: String, latencyMs: Long?) { onAttempt(variant, result) }
    fun onResult(result: String, codes: List<ProductCode>) {}
    companion object { val NONE = object : ScanDiagnostics {} }
}

sealed interface ScanOutcome {
    data class Decoded(val code: ProductCode) : ScanOutcome
    data class Confirm(val codes: List<ProductCode>) : ScanOutcome
    data object Unreadable : ScanOutcome
    /** Play Services OCR model is not downloaded yet; barcode decoding itself still works. */
    data object TextModelNotReady : ScanOutcome
}

/** Raised by the OCR adapter when Google Play has not finished delivering the text model. */
class TextRecognitionNotReady(message: String = "text recognition model not ready") : Exception(message)

private fun List<ProductCode>.validDistinct() = filter { it.isValid() }.distinctBy { it.key }.take(8)

/** Never replace O with 0, manufacture a check digit, or join unrelated OCR lines. */
fun ocrProductCodes(lines: List<String>): List<ProductCode> = lines.take(64).flatMap { line ->
    Regex("[0-9][0-9 \\t-]*[0-9]").findAll(line.take(256)).flatMap { match ->
        val digits = match.value.filter { it in '0'..'9' }
        listOfNotNull(ProductCode(digits), if (digits.length == 8) ProductCode(digits, upcE = true) else null)
    }.toList()
}.validDistinct()

/** Original plus five ordered retries, each derived from the original, never an accumulating blur. */
suspend fun decodeProductImage(
    image: ScanPixels,
    decode: suspend (ScanPixels, ScanVariant) -> List<ProductCode>,
    recognizeText: suspend (ScanPixels) -> List<String>,
    diagnostics: ScanDiagnostics = ScanDiagnostics.NONE,
    recognizeLayout: (suspend (ScanPixels) -> List<OcrLine>)? = null,
): ScanOutcome {
    for (variant in ScanVariant.entries) {
        val started = if (diagnostics.enabled) System.nanoTime() else 0L
        val codes = try {
            image.variant(variant).let { pixels ->
                decode(pixels, variant).also { raw ->
                    if (diagnostics.enabled) diagnostics.onEvent("decode_counts", "summary", mapOf("variant" to variant.name.lowercase(),
                        "raw_candidates" to raw.size.toString(), "accepted_candidates" to raw.count { it.isValid() }.toString()))
                }.validDistinct().also {
                    if (it.isEmpty() && variant == ScanVariant.SHARPEN && diagnostics.enabled) diagnostics.onFailedVariant(pixels)
                }
            }.also {
                diagnostics.onAttempt(variant, if (it.isEmpty()) "no_code" else "decoded", if (diagnostics.enabled) (System.nanoTime() - started) / 1_000_000 else null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            diagnostics.onAttempt(variant, "decoder_error", if (diagnostics.enabled) (System.nanoTime() - started) / 1_000_000 else null)
            emptyList()
        }
        if (codes.isNotEmpty()) {
            diagnostics.onResult("barcode", codes)
            return if (codes.size == 1) ScanOutcome.Decoded(codes.single()) else ScanOutcome.Confirm(codes)
        }
    }
    val ocrStart = if (diagnostics.enabled) System.nanoTime() else 0L
    diagnostics.onEvent("ocr", "start")
    val codes = try {
        (if (recognizeLayout != null) ocrProductCodesFromLayout(recognizeLayout(image)) else ocrProductCodes(recognizeText(image))).also { diagnostics.onEvent("ocr", "ready", mapOf("candidates" to it.size.toString()), if (diagnostics.enabled) (System.nanoTime() - ocrStart) / 1_000_000 else null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TextRecognitionNotReady) {
        diagnostics.onResult("ocr_model_not_ready", emptyList())
        return ScanOutcome.TextModelNotReady
    } catch (_: Exception) {
        diagnostics.onResult("ocr_error", emptyList())
        emptyList()
    }
    diagnostics.onResult(if (codes.isEmpty()) "unreadable" else "ocr_confirm", codes)
    // Even a single OCR candidate requires a human check; a checksum is not proof of identity.
    return if (codes.isEmpty()) ScanOutcome.Unreadable else ScanOutcome.Confirm(codes)
}

/** ARGB pixels only: transforms can be regression-tested without Android graphics or a camera. */
data class ScanPixels(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
        require(argb.size == width * height)
    }
    fun variant(variant: ScanVariant): ScanPixels = when (variant) {
        ScanVariant.ORIGINAL -> this
        ScanVariant.CROP -> {
            val cw = (width * .8).toInt().coerceAtLeast(1)
            val ch = (height * .8).toInt().coerceAtLeast(1)
            val scale = min(2.0, sqrt(MAX_PIXELS.toDouble() / (cw.toDouble() * ch)))
            val w = (cw * scale).toInt().coerceAtLeast(1)
            val h = (ch * scale).toInt().coerceAtLeast(1)
            ScanPixels(w, h, IntArray(w * h) { i ->
                argb[((height - ch) / 2 + i / w * ch / h) * width + (width - cw) / 2 + (i % w) * cw / w]
            })
        }
        ScanVariant.ROTATE -> ScanPixels(height, width, IntArray(argb.size) { i ->
            argb[(height - 1 - i % height) * width + i / height]
        })
        ScanVariant.CONTRAST -> {
            val grey = greys()
            val lo = grey.minOrNull()!!; val hi = grey.maxOrNull()!!
            ScanPixels(width, height, IntArray(argb.size) { i ->
                mono(if (hi == lo) grey[i] else (grey[i] - lo) * 255 / (hi - lo))
            })
        }
        ScanVariant.THRESHOLD -> {
            val grey = greys()
            val stride = width + 1
            val integral = IntArray(stride * (height + 1))
            for (y in 0 until height) {
                var row = 0
                for (x in 0 until width) {
                    row += grey[y * width + x]
                    integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
                }
            }
            ScanPixels(width, height, IntArray(argb.size) { i ->
                val x = i % width; val y = i / width
                val l = (x - 10).coerceAtLeast(0); val r = (x + 11).coerceAtMost(width)
                val t = (y - 10).coerceAtLeast(0); val b = (y + 11).coerceAtMost(height)
                val sum = integral[b * stride + r] - integral[t * stride + r] - integral[b * stride + l] + integral[t * stride + l]
                mono(if (grey[i] >= sum / ((r - l) * (b - t)) - 8) 255 else 0)
            })
        }
        ScanVariant.SHARPEN -> {
            // A median pass removes isolated camera speckles before sharpening the bar edges.
            val input = greys()
            val window = IntArray(9)
            val grey = IntArray(input.size)
            for (y in 0 until height) for (x in 0 until width) {
                var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    window[n++] = input[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)]
                }
                window.sort()
                grey[y * width + x] = window[4]
            }
            ScanPixels(width, height, IntArray(argb.size) { i ->
                val x = i % width; val y = i / width
                mono((5 * grey[i] - grey[y * width + (x - 1).coerceAtLeast(0)] -
                    grey[y * width + (x + 1).coerceAtMost(width - 1)] -
                    grey[(y - 1).coerceAtLeast(0) * width + x] -
                    grey[(y + 1).coerceAtMost(height - 1) * width + x]).coerceIn(0, 255))
            })
        }
    }
    private fun greys() = IntArray(argb.size) { i ->
        val p = argb[i]
        ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000
    }
    companion object {
        const val MAX_PIXELS = 2_100_000
        private fun mono(grey: Int) = -0x1000000 or (grey shl 16) or (grey shl 8) or grey
    }
}
