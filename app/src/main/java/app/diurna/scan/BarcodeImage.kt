package app.diurna.scan

import app.diurna.domain.ProductCode
import app.diurna.domain.ScanDiagnostics
import app.diurna.domain.ScanOutcome
import app.diurna.domain.ScanPixels
import app.diurna.domain.TextRecognitionNotReady
import app.diurna.domain.decodeProductImage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.compose.runtime.staticCompositionLocalOf
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

val LocalScanDiagnostics = staticCompositionLocalOf { ScanDiagnostics.NONE }
const val MAX_SCAN_BYTES = 2L * 1024 * 1024

/** Private, metadata-free JPEG is available to diagnostics only until this call returns. */
suspend fun readCapturedBarcode(raw: File, diagnostics: ScanDiagnostics = ScanDiagnostics.NONE): ScanOutcome =
    withContext(Dispatchers.IO) {
        var saved: File? = null
        try {
            if (raw.length() !in 1..16L * 1024 * 1024) throw IOException("invalid capture size")
            val bitmap = try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(raw)) { decoder, info, _ ->
                    val w = info.size.width; val h = info.size.height
                    val scale = min(1.0, min(1920.0 / maxOf(w, h), sqrt(ScanPixels.MAX_PIXELS / (w.toDouble() * h))))
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setOnPartialImageListener { false }
                }
            } catch (e: Exception) { throw IOException("invalid capture", e) }
            try {
                val bytes = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes)
                if (bytes.size() > MAX_SCAN_BYTES) throw IOException("capture too large")
                saved = File.createTempFile("barcode-normalized-", ".jpg", raw.parentFile)
                saved.writeBytes(bytes.toByteArray())
                diagnostics.onCaptured(saved)
            } finally { bitmap.recycle() }
            // Scan the exact metadata-free file supplied to diagnostics, so a report reproduces it.
            val normalized = BitmapFactory.decodeFile(saved!!.absolutePath) ?: throw IOException("invalid saved photo")
            val pixels = try {
                val argb = IntArray(normalized.width * normalized.height)
                normalized.getPixels(argb, 0, normalized.width, 0, 0, normalized.width, normalized.height)
                ScanPixels(normalized.width, normalized.height, argb)
            } finally { normalized.recycle() }
            // Do not retain the larger raw capture while trying the variants.
            raw.delete()
            withTimeout(20_000) { readBarcodePixels(pixels, diagnostics) }
        } finally {
            raw.delete()
            saved?.delete()
        }
    }

suspend fun readBarcodePixels(pixels: ScanPixels, diagnostics: ScanDiagnostics = ScanDiagnostics.NONE): ScanOutcome =
    withContext(Dispatchers.Default) {
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
            .setBarcodeFormats(PRODUCT_FORMATS.first(), *PRODUCT_FORMATS.drop(1).toIntArray()).build())
        val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            decodeProductImage(pixels, { image, _ ->
                image.processBitmap { scanner.process(it) }.mapNotNull { barcode ->
                    barcode.rawValue?.let { ProductCode(it, barcode.format == Barcode.FORMAT_UPC_E) }
                }
            }, { image ->
                try {
                    image.processBitmap { text.process(it) }.textBlocks.flatMap { block -> block.lines.map { it.text } }
                } catch (e: Exception) {
                    if (e.isTextModelNotReady()) throw TextRecognitionNotReady()
                    throw e
                }
            }, diagnostics)
        } finally {
            scanner.close()
            text.close()
        }
    }

private suspend fun <T> ScanPixels.processBitmap(process: (InputImage) -> Task<T>): T =
    suspendCancellableCoroutine { continuation ->
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        try {
            process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                // ML Kit can finish after the dialog closes; keep its input alive until completion.
                bitmap.recycle()
                if (continuation.isActive) {
                    if (task.isSuccessful) continuation.resume(task.result)
                    else continuation.resumeWithException(task.exception ?: IOException("recognition failed"))
                }
            }
        } catch (e: Exception) {
            bitmap.recycle()
            continuation.resumeWithException(e)
        }
    }

private fun Throwable.isTextModelNotReady(): Boolean {
    var cur: Throwable? = this
    while (cur != null) {
        if (cur is MlKitException && cur.errorCode == MlKitException.UNAVAILABLE) return true
        val message = cur.message.orEmpty().lowercase()
        if ("optional module" in message || "model is not available" in message || "waiting for the text" in message) return true
        cur = cur.cause
    }
    return false
}
