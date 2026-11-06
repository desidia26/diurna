package app.diurna.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.diurna.domain.PhotoSizing
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Decode, EXIF uprighting, camera scratch import, content-URI pick, and downscale integration.
 * Runs on the CI emulator (no local KVM required for authorship).
 */
@RunWith(AndroidJUnit4::class)
class PhotoImporterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val photoDir = File(context.cacheDir, "importer-photos-${System.nanoTime()}").apply { mkdirs() }
    private val photos = FoodPhotos(photoDir)
    private val importer = PhotoImporter(context, photos)

    @After fun tearDown() {
        photoDir.deleteRecursively()
        importer.captureFile.delete()
        importer.captureFile.parentFile?.listFiles()?.forEach { it.delete() }
    }

    private fun jpeg(width: Int, height: Int, paint: (Bitmap) -> Unit = { it.eraseColor(Color.RED) }): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        paint(bmp)
        val out = ByteArrayOutputStream()
        assertTrue(bmp.compress(Bitmap.CompressFormat.JPEG, 90, out))
        bmp.recycle()
        return out.toByteArray()
    }

    private fun decodeStored(name: String): Bitmap {
        val file = photos.file(name)!!
        return BitmapFactory.decodeFile(file.path)!!
    }

    @Test fun importCaptureStoresDownscaledJpegAndRemovesScratch() = runTest {
        val big = jpeg(3200, 2400)
        importer.captureFile.writeBytes(big)
        assertTrue(importer.captureFile.length() > 0)
        val name = importer.importCapture()
        assertNotNull(name)
        assertTrue(PhotoSizing.FILE_NAME.matches(name!!))
        assertFalse("scratch removed after import", importer.captureFile.exists())
        val stored = decodeStored(name)
        assertEquals(PhotoSizing.MAX_EDGE, maxOf(stored.width, stored.height))
        assertTrue(File(photoDir, name).length() in 1..PhotoSizing.MAX_BYTES.toLong())
        stored.recycle()
    }

    @Test fun importCaptureOfEmptyScratchReturnsNull() = runTest {
        importer.captureFile.writeBytes(ByteArray(0))
        assertNull(importer.importCapture())
        assertFalse(importer.captureFile.exists())
        assertEquals(0, photoDir.listFiles()?.size ?: 0)
    }

    @Test fun importCaptureOfCorruptBytesReturnsNull() = runTest {
        importer.captureFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01, 0x02))
        assertNull(importer.importCapture())
        assertEquals(0, photoDir.listFiles()?.size ?: 0)
    }

    @Test fun importFromContentUri() = runTest {
        val src = File(context.cacheDir, "picked-${System.nanoTime()}.jpg")
        src.writeBytes(jpeg(800, 600) { bmp ->
            // left half blue, right half green — so we can spot a successful decode
            for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
                bmp.setPixel(x, y, if (x < bmp.width / 2) Color.BLUE else Color.GREEN)
            }
        })
        try {
            val uri = Uri.fromFile(src)
            val name = importer.import(uri)
            assertNotNull(name)
            val stored = decodeStored(name!!)
            assertEquals(800, stored.width)
            assertEquals(600, stored.height)
            // JPEG round-trip softens exact ARGB; check the dominant channel instead.
            val left = stored.getPixel(10, 10)
            val right = stored.getPixel(790, 10)
            assertTrue("left should stay blue-ish", Color.blue(left) > Color.red(left) + 40 && Color.blue(left) > Color.green(left) + 40)
            assertTrue("right should stay green-ish", Color.green(right) > Color.red(right) + 40 && Color.green(right) > Color.blue(right) + 40)
            stored.recycle()
        } finally {
            src.delete()
        }
    }

    @Test fun exifOrientationIsAppliedWhenDecoding() = runTest {
        // 40×20 landscape painted left-red / right-blue; EXIF rotate 90 → stored upright 20×40
        // with red on top after ImageDecoder applies orientation.
        val src = File(context.cacheDir, "exif-${System.nanoTime()}.jpg")
        src.writeBytes(jpeg(40, 20) { bmp ->
            for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
                bmp.setPixel(x, y, if (x < bmp.width / 2) Color.RED else Color.BLUE)
            }
        })
        ExifInterface(src.path).run {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        try {
            val name = importer.import(Uri.fromFile(src))
            assertNotNull(name)
            val stored = decodeStored(name!!)
            // ImageDecoder uprights: 40×20 + 90° → 20×40
            assertEquals(20, stored.width)
            assertEquals(40, stored.height)
            stored.recycle()
        } finally {
            src.delete()
        }
    }

    @Test fun smallPhotosAreNotEnlarged() = runTest {
        importer.captureFile.writeBytes(jpeg(320, 240))
        val name = importer.importCapture()!!
        val stored = decodeStored(name)
        assertEquals(320, stored.width)
        assertEquals(240, stored.height)
        stored.recycle()
    }
}
