package app.diurna.scan

import app.diurna.domain.LiveScanSession
import app.diurna.domain.ProductCode
import app.diurna.domain.ScanDiagnostics
import app.diurna.domain.ScanOutcome
import app.diurna.domain.ScanPixels

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.oned.EAN13Writer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BarcodeImageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun barcodeDecoderReadsSavedImageAndRemovesScratchFiles() = runBlocking {
        val matrix = EAN13Writer().encode("4006381333931", BarcodeFormat.EAN_13, 600, 250)
        val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) bitmap.setPixel(x, y, if (matrix[x,y]) Color.BLACK else Color.WHITE)
        val raw = File.createTempFile("scanner-test", ".jpg", context.cacheDir)
        raw.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        var attached: File? = null
        val diagnostics = object : ScanDiagnostics {
            override fun onCaptured(file: File) {
                assertTrue(file.exists())
                assertTrue(file.length() in 1..MAX_SCAN_BYTES)
                attached = file
            }
        }
        assertEquals(ScanOutcome.Decoded(ProductCode("4006381333931")), readCapturedBarcode(raw, diagnostics))
        assertNotNull(attached)
        assertFalse(raw.exists())
        assertFalse(attached!!.exists())
    }

    /** A missing model is an explicit skipped correctness test, NEVER a passing OCR assertion. */
    @Test fun playServicesOcrRecognizesKnownDigitsWhenModelAvailable() = runBlocking {
        val client = ModuleInstall.getClient(context)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var ready = false
        var availability = "unavailable"
        try {
            ready = Tasks.await(client.areModulesAvailable(recognizer), 10, TimeUnit.SECONDS).areModulesAvailable()
            if (!ready) {
                // Existing Google Latin OCR module only; no new dependency or third-party model download.
                Tasks.await(client.installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build()),10,TimeUnit.SECONDS)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (!ready && System.nanoTime() < deadline) {
                    Thread.sleep(2000)
                    ready = Tasks.await(client.areModulesAvailable(recognizer),5,TimeUnit.SECONDS).areModulesAvailable()
                }
            }
        } catch (e: Exception) { availability = (e.cause as? com.google.android.gms.common.api.ApiException)?.let { "api_status_${it.statusCode}" } ?: e.javaClass.simpleName }
        finally { recognizer.close() }
        val bitmap = Bitmap.createBitmap(1100, 220, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("4006381333931", 40f, 145f, Paint().apply { color = Color.BLACK; textSize = 100f; isAntiAlias = true })
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = ScanPixels(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        val result = kotlinx.coroutines.withTimeout(20_000) { readBarcodePixels(image) }
        assumeTrue("OCR correctness NOT VALIDATED: Google Play module unavailable ($availability)", ready || result != ScanOutcome.TextModelNotReady)
        assertTrue("Model reported ready but OCR did not recognize known digits: $result", result is ScanOutcome.Confirm)
        assertEquals(listOf(ProductCode("4006381333931")), (result as ScanOutcome.Confirm).codes)
    }

    @Test fun corruptOrEmptyCaptureIsRejectedAndDeleted() = runBlocking {
        for (bytes in listOf(byteArrayOf(), "not an image".toByteArray())) {
            val file = File.createTempFile("scanner-corrupt", ".jpg", context.cacheDir).apply { writeBytes(bytes) }
            try {
                readCapturedBarcode(file)
                fail("invalid image must not reach a lookup")
            } catch (_: java.io.IOException) { }
            assertFalse(file.exists())
        }
    }
}
