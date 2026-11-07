package app.diurna.scan

import app.diurna.domain.ProductCode
import app.diurna.domain.ScanOutcome
import app.diurna.domain.ScanPixels
import app.diurna.domain.ScanVariant
import app.diurna.domain.decodeProductImage

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import javax.imageio.ImageIO
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BarcodeFixtureTest {
    private fun fixture(name: String): ScanPixels? {
        val image = javaClass.getResourceAsStream("/barcodes/$name.png")!!.use { ImageIO.read(it) } ?: return null
        return ScanPixels(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    }
    private fun read(p: ScanPixels): List<ProductCode> = try {
        val source = RGBLuminanceSource(p.width, p.height, p.argb)
        // Deliberately no TRY_HARDER: the pipeline's rotation retry, not ZXing's, must rescue it.
        val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13)))
        listOf(ProductCode(result.text))
    } catch (_: ReaderException) { emptyList() }

    @Test fun noisyFixturesAndRotatedRetry() = runTest {
        val expected = mapOf("clean" to 0, "blur" to 0, "noise" to 5, "perspective" to 0,
            "low-resolution" to 0, "rotate" to 2)
        expected.forEach { (name, attempt) ->
            val seen = mutableListOf<ScanVariant>()
            val result = decodeProductImage(fixture(name)!!, { p, variant -> seen += variant; read(p) }, { emptyList() })
            assertEquals(name, ScanOutcome.Decoded(ProductCode("4006381333931")), result)
            assertEquals(name, attempt + 1, seen.size)
        }
    }
    @Test fun irrecoverableFixturesDoNotInventProducts() = runTest {
        listOf("blank-fail", "glare-fail", "clipped-fail").forEach { name ->
            var attempts = 0
            assertEquals(name, ScanOutcome.Unreadable, decodeProductImage(fixture(name)!!,
                { p, _ -> attempts++; read(p) }, { emptyList() }))
            assertEquals(name, 6, attempts)
        }
        assertNull(fixture("corrupt-fail"))
    }
}
