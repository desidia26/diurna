package app.diurna.scan

import app.diurna.domain.ProductCode
import app.diurna.domain.ScanOutcome
import app.diurna.domain.ScanPixels
import app.diurna.domain.decodeProductImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Larry's real package photos (ai-evals/images/real, metadata stripped). ZXing stands in for the
 * on-device ML Kit decoder, so these pin the retry pipeline, not ML Kit accuracy. Codes are as
 * printed and checksum-valid. RELIABLE photos must decode to exactly that code; UNCERTAIN ones
 * (partial, glossy or tiny barcodes) may fail, but must never decode to a different product.
 */
class RealPackageBarcodeTest {
    private val reliable = mapOf(
        "flax-barcode" to "0860009719569",
        "almonds" to "0076991000411",
    )
    private val uncertain = mapOf(
        // Partly hidden at the bottom right; digits read by eye, not asserted.
        "pecans" to "0078742202334",
        // Small and rotated; the legible digits are 0 31200 00678, and ZXing's checksum-valid
        // read supplies the final 3. Not asserted because ML Kit may not manage it.
        "craisins-rotated" to "0031200006783",
        // No barcode on the panel side.
        "flax-label" to null,
    )

    private fun dir(): File = listOf(File("../ai-evals/images/real"), File("ai-evals/images/real")).first { it.isDirectory }

    private fun pixels(name: String): ScanPixels {
        val image = ImageIO.read(File(dir(), "$name.jpg"))!!
        return ScanPixels(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    }

    private fun zxing(p: ScanPixels): List<ProductCode> = try {
        val result = MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(p.width, p.height, p.argb))),
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.UPC_A, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
        listOf(ProductCode(result.text))
    } catch (_: ReaderException) { emptyList() }

    private suspend fun decode(name: String): ScanOutcome =
        decodeProductImage(pixels(name), { p, _ -> zxing(p) }, { emptyList() })

    private fun normalized(o: ScanOutcome): String? =
        (o as? ScanOutcome.Decoded)?.code?.digits?.let { app.diurna.data.normalizeBarcode(it) }

    @Test fun reliablePhotosDecodeToThePrintedCode() = runTest {
        reliable.forEach { (name, code) -> assertEquals(name, code, normalized(decode(name))) }
    }

    @Test fun uncertainPhotosNeverDecodeToAnotherProduct() = runTest {
        uncertain.forEach { (name, code) ->
            val got = normalized(decode(name)) ?: return@forEach
            assertTrue("$name decoded $got", code != null && got == code)
        }
    }
}
