package app.diurna.scan

import app.diurna.domain.ProductCode
import app.diurna.domain.ScanDiagnostics
import app.diurna.domain.ScanOutcome
import app.diurna.domain.TextRecognitionNotReady
import app.diurna.domain.ScanPixels
import app.diurna.domain.ScanVariant
import app.diurna.domain.decodeProductImage
import app.diurna.domain.ocrProductCodes

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BarcodeDecodeTest {
    private val image = ScanPixels(12, 12, IntArray(144) { -1 })
    private val valid = ProductCode("4006381333931")

    @Test fun validatesCheckDigitsAndFormats() {
        assertTrue(valid.isValid())
        assertTrue(ProductCode("036000291452").isValid())
        assertTrue(ProductCode("96385074").isValid())
        assertTrue(ProductCode("04252614", upcE = true).isValid())
        listOf("4006381333932", "123", "400638133393１", " 4006381333931").forEach {
            assertFalse(ProductCode(it).isValid())
        }
        assertFalse(ProductCode("24252614", upcE = true).isValid())
        assertFalse(ProductCode("04252615", upcE = true).isValid())
    }

    @Test fun originalStopsImmediately() = runTest {
        val attempts = mutableListOf<ScanVariant>()
        var ocr = false
        val result = decodeProductImage(image, { _, variant -> attempts += variant; listOf(valid) }, { ocr = true; emptyList() })
        assertEquals(ScanOutcome.Decoded(valid), result)
        assertEquals(listOf(ScanVariant.ORIGINAL), attempts)
        assertFalse(ocr)
    }

    @Test fun fiveRetriesThenOcrAndInvalidReadsNeverEscape() = runTest {
        val attempts = mutableListOf<ScanVariant>()
        val result = decodeProductImage(image, { _, variant -> attempts += variant; listOf(ProductCode("4006381333932")) }, { listOf("4006381333931") })
        assertEquals(ScanVariant.entries, attempts)
        assertEquals(6, attempts.size)
        assertEquals(ScanOutcome.Confirm(listOf(valid)), result) // OCR always checked by the person
    }

    @Test fun stopAtFirstSuccessfulVariant() = runTest {
        val attempts = mutableListOf<ScanVariant>()
        val result = decodeProductImage(image, { _, variant ->
            attempts += variant
            if (variant == ScanVariant.ROTATE) listOf(valid) else emptyList()
        }, { error("OCR must not run") })
        assertEquals(ScanOutcome.Decoded(valid), result)
        assertEquals(listOf(ScanVariant.ORIGINAL, ScanVariant.CROP, ScanVariant.ROTATE), attempts)
    }

    @Test fun multipleBarcodesRequireConfirmationInsteadOfArbitraryFirst() = runTest {
        val other = ProductCode("036000291452")
        assertEquals(ScanOutcome.Confirm(listOf(valid, other)),
            decodeProductImage(image, { _, _ -> listOf(valid, other) }, { emptyList() }))
    }

    @Test fun ocrAcceptsSpacedDigitsButNeverInventsOrRepairsDigits() {
        assertEquals(listOf(valid), ocrProductCodes(listOf("4 006381 333931", "4006381333931", "4006381333932")))
        assertTrue(ocrProductCodes(listOf("4OO6381333931", "1234", "9999999999999999999999")).isEmpty())
        assertTrue(ocrProductCodes(listOf("400638", "1333931")).isEmpty()) // don't join unrelated lines
        val short = ocrProductCodes(listOf("04252614"))
        assertTrue(short.contains(ProductCode("04252614", upcE = true)))
    }

    @Test fun noCodeStaysNoCodeAndDecoderFailuresAreCounted() = runTest {
        val attempts = mutableListOf<String>()
        val diagnostics = object : ScanDiagnostics {
            override fun onAttempt(variant: ScanVariant, result: String) { attempts += result }
        }
        assertEquals(ScanOutcome.Unreadable, decodeProductImage(image, { _, _ -> error("decoder failed") }, { emptyList() }, diagnostics))
        assertEquals(List(6) { "decoder_error" }, attempts)
    }

    @Test fun cancellationIsNotSwallowedAsAnotherRetry() = runTest {
        var attempts = 0
        try {
            decodeProductImage(image, { _, _ -> attempts++; throw kotlinx.coroutines.CancellationException() }, { error("OCR must not run") })
            fail("cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, attempts)
    }

    @Test fun ocrFailureNeverReachesLookup() = runTest {
        assertEquals(ScanOutcome.Unreadable, decodeProductImage(image, { _, _ -> emptyList() }, { error("OCR unavailable") }))
    }

    @Test fun ocrModelNotReadyIsDistinctAndBarcodePathStillRuns() = runTest {
        val attempts = mutableListOf<ScanVariant>()
        val results = mutableListOf<String>()
        val diagnostics = object : ScanDiagnostics {
            override fun onAttempt(variant: ScanVariant, result: String) { attempts += variant }
            override fun onResult(result: String, codes: List<ProductCode>) { results += result }
        }
        assertEquals(
            ScanOutcome.TextModelNotReady,
            decodeProductImage(
                image,
                { _, _ -> emptyList() },
                { throw TextRecognitionNotReady() },
                diagnostics,
            ),
        )
        assertEquals(ScanVariant.entries, attempts) // every barcode variant still tried first
        assertEquals(listOf("ocr_model_not_ready"), results)
    }

    @Test fun retryGeometryIsDeterministicAndOriginalIsNotMutated() {
        val source = ScanPixels(3, 2, intArrayOf(1,2,3,4,5,6))
        val rotated = source.variant(ScanVariant.ROTATE)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        assertArrayEquals(intArrayOf(4,1,5,2,6,3), rotated.argb)
        ScanVariant.entries.forEach { source.variant(it) }
        assertArrayEquals(intArrayOf(1,2,3,4,5,6), source.argb)
    }

    @Test fun equivalentUpcAndEanReadsAreOneProduct() = runTest {
        val upc = ProductCode("036000291452")
        assertEquals(ScanOutcome.Decoded(upc), decodeProductImage(image,
            { _, _ -> listOf(upc, ProductCode("0036000291452")) }, { emptyList() }))
    }

    @Test fun variantsStayBoundedAndPreserveShape() {
        ScanVariant.entries.forEach { variant ->
            val pixels = image.variant(variant)
            assertTrue(pixels.width * pixels.height <= ScanPixels.MAX_PIXELS)
            assertEquals(pixels.width * pixels.height, pixels.argb.size)
        }
    }
}
