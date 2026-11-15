package app.diurna.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.diurna.ai.PackageCrops
import app.diurna.data.PackageCrop
import app.diurna.domain.PhotoSizing
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ML Kit's bundled object detector on the eval photos finds the packages the eval harness crops
 * from (ai-evals/detections.json, recorded offline from the same model), so the harness's numbers
 * describe what the phone does.
 */
@RunWith(AndroidJUnit4::class)
class PackageDetectorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun fixture(rel: String): File {
        val f = File.createTempFile("detector-", ".jpg", context.cacheDir)
        instrumentation.context.assets.open(rel).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun golden(rel: String): List<PackageCrops.Box> {
        val json = JSONObject(instrumentation.context.assets.open("detections.json").bufferedReader().readText())
        val arr = json.getJSONObject("images").getJSONArray(rel)
        return (0 until arr.length()).map { i -> arr.getJSONObject(i).getJSONArray("box").let { b -> PackageCrops.Box(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)) } }
    }

    private fun planned(rel: String): Pair<List<PackageCrops.Box>?, List<PackageCrops.Box>?> {
        val f = fixture(rel)
        try {
            val found = runBlocking { PackageDetector.detect(f, SLOW_EMULATOR_MS) }
            return PackageCrops.plan(found) to PackageCrops.plan(golden(rel))
        } finally { f.delete() }
    }

    /** Every recorded crop has a device crop on the same package, and the device finds no extra ones. */
    private fun assertSamePackages(rel: String) {
        val (device, recorded) = planned(rel)
        assertNotNull("$rel: device found fewer than two packages", device)
        val want = recorded!!
        val got = device!!
        assertEquals("$rel: device crops $got vs recorded $want", want.size, got.size)
        want.forEach { w -> assertTrue("$rel: no device crop for recorded $w in $got", got.any { it.iou(w) >= 0.5 }) }
    }

    @Test fun sixPackageCounterPhotoMatchesTheRecordedCrops() = assertSamePackages("images/real/oatmeal-six.jpg")

    @Test fun montageMatchesTheRecordedCrops() = assertSamePackages("images/real/montage-pecans-almonds-flax.jpg")

    @Test fun singlePackagePhotosAreReadWhole() {
        for (rel in listOf("images/real/pecans.jpg", "images/real/flax-label.jpg", "images/real/craisins-rotated.jpg")) {
            assertNull("$rel should be read whole", planned(rel).first)
        }
    }

    @Test fun blankPhotoFindsNothingToCrop() = runBlocking {
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFEEEEEE.toInt()) }
        assertNull(PackageCrops.plan(PackageDetector.detect(bmp)))
    }

    @Test fun cropIsAMetadataFreeJpegAtMostMaxEdge() = runBlocking {
        val f = fixture("images/real/oatmeal-six.jpg")
        try {
            val box = PackageCrops.plan(golden("images/real/oatmeal-six.jpg"))!!.first().padded()
            val jpeg = PackageCrop.cut(f, box)!!
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
            assertTrue(maxOf(opts.outWidth, opts.outHeight) <= PhotoSizing.MAX_EDGE)
            assertEquals(box.width * 2048, opts.outWidth.toDouble(), 3.0)
            assertFalse("crop carries EXIF", String(jpeg, Charsets.ISO_8859_1).contains("Exif\u0000"))
        } finally { f.delete() }
    }

    @Test fun photoSourceDetectsAndCutsFromItsFile() = runBlocking {
        val f = fixture("images/real/montage-pecans-almonds-flax.jpg")
        try {
            val src = PhotoPackageSource(f, SLOW_EMULATOR_MS)
            val plan = PackageCrops.plan(src.detect())!!
            assertTrue(plan.all { src.cut(it.padded()) != null })
        } finally { f.delete() }
    }

    private companion object {
        /** CI and the dev emulator run without acceleration, where a cold ML Kit call takes 10–50 s. */
        const val SLOW_EMULATOR_MS = 180_000L
    }
}
