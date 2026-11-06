package app.diurna.scan

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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BarcodeImageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun bundledBarcodeDecoderReadsSavedImageAndRemovesScratchFiles() = runBlocking {
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

    @Test fun bundledOcrReadsPrintedDigitsOfflineAndAlwaysAsksForConfirmation() = runBlocking {
        val bitmap = Bitmap.createBitmap(1100, 220, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText("4006381333931", 40f, 145f, Paint().apply { color = Color.BLACK; textSize = 100f; isAntiAlias = true })
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = ScanPixels(bitmap.width, bitmap.height, pixels)
        bitmap.recycle()
        val result = readBarcodePixels(image)
        assertEquals(ScanOutcome.Confirm(listOf(ProductCode("4006381333931"))), result)
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
