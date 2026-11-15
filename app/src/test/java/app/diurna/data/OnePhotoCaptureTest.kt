package app.diurna.data

import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.NoApiKey
import app.diurna.ai.PackageReading
import app.diurna.data.LibraryRepository.ScanResult
import app.diurna.data.fdc.FdcClient
import app.diurna.domain.PackageMerge.Source
import app.diurna.domain.ProductCode
import app.diurna.domain.ScanOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One photo: barcode decode + lookups run beside the label read, then merge. */
class OnePhotoCaptureTest {
    private val almondsOff = """{"status":1,"product":{"product_name":"Almonds","serving_size":"28 g","serving_quantity":28,
        "nutriments":{"energy-kcal_100g":600,"proteins_100g":21,"carbohydrates_100g":21,"fat_100g":50}}}"""

    private fun found(code: String): ScanResult {
        val snap = parseOffSnapshot(almondsOff, 1L)!!
        return ScanResult.Found(parseOffProduct(almondsOff)!!.copy(barcode = code, offSnapshotJson = snap.encode()))
    }

    private val fdcAlmonds = FdcClient.parseBranded(JSONObject("""{"fdcId":9,"description":"WHOLE ALMONDS","gtinUpc":"076991000411",
        "servingSize":28,"servingSizeUnit":"g","ingredients":"ALMONDS.",
        "foodNutrients":[{"nutrientId":1008,"value":607},{"nutrientId":1003,"value":21.4},{"nutrientId":1005,"value":21.4},{"nutrientId":1004,"value":53.6}]}"""))!!

    private val label = PackageReading(true, "Whole Almonds", "Acme", "1 oz (28g)", 28.0, 16.0,
        mapOf("kcal" to 170.0, "protein_g" to 6.0, "carbs_g" to 6.0, "fat_g" to 15.0), "Almonds.")

    private fun capture(
        lookup: suspend (String, Boolean) -> ScanResult = { c, _ -> found(c) },
        fdc: suspend (String) -> app.diurna.data.fdc.FdcBrandedFood? = { fdcAlmonds },
        reader: (suspend (ByteArray) -> PackageReading)? = { label },
        online: () -> Boolean = { true },
        events: MutableList<String> = mutableListOf(),
    ) = OnePhotoCapture(lookup, fdc, reader, online) { name, result, _ -> events += "$name:$result" }

    private val decoded: suspend () -> ScanOutcome = { ScanOutcome.Decoded(ProductCode("076991000411")) }

    @Test fun barcodeAndLabelBothFeedTheMergeWithLabelFirst() = runBlocking {
        val c = capture().run(ByteArray(3), decoded)
        assertEquals("0076991000411", c.barcode)
        assertTrue(c.label is OnePhotoCapture.LabelRead.Read)
        val m = c.merged
        assertEquals(Source.LABEL, m.per100g.getValue("kcal").source)
        assertEquals(170.0 / 28 * 100, m.per100g.getValue("kcal").value, 0.01)
        assertEquals(setOf(Source.LABEL, Source.FDC_BRANDED, Source.OFF), c.inputs.map { it.source }.toSet())
        assertEquals("Acme Whole Almonds", c.suggestedName)
        assertFalse(c.labelLater)
    }

    @Test fun labelReadRunsWhileTheLookupIsStillGoing() = runBlocking {
        val labelStarted = CompletableDeferred<Unit>()
        val c = capture(
            lookup = { code, _ -> withTimeout(5_000) { labelStarted.await() }; found(code) },
            reader = { labelStarted.complete(Unit); label },
        ).run(ByteArray(3), decoded)
        assertTrue(c.code is OnePhotoCapture.CodeState.Read)
    }

    @Test fun noAiKeyKeepsTheBarcodeResultAndOffersALabelReadLater() = runBlocking {
        val c = capture(reader = { throw NoApiKey("Add a key") }).run(ByteArray(3), decoded)
        val l = c.label as OnePhotoCapture.LabelRead.Skipped
        assertEquals("no_key", l.reason)
        assertTrue(c.labelLater)
        assertEquals(Source.FDC_BRANDED, c.merged.per100g.getValue("kcal").source)
    }

    @Test fun offlineSkipsTheAiCallEntirely() = runBlocking {
        var called = false
        val c = capture(reader = { called = true; label }, online = { false }).run(ByteArray(3), decoded)
        assertFalse(called)
        assertEquals("offline", (c.label as OnePhotoCapture.LabelRead.Skipped).reason)
        assertTrue(c.labelLater)
    }

    @Test fun noReaderConfiguredIsANoKeySkip() = runBlocking {
        val c = capture(reader = null).run(ByteArray(3), decoded)
        assertEquals("no_key", (c.label as OnePhotoCapture.LabelRead.Skipped).reason)
    }

    @Test fun networkTroubleOnTheLabelReadIsOfflineAndAReadFailureIsFailed() = runBlocking {
        val a = capture(reader = { throw EstimateRetryable() }).run(ByteArray(3), decoded)
        assertEquals("offline", (a.label as OnePhotoCapture.LabelRead.Skipped).reason)
        val b = capture(reader = { throw EstimateFailed("Couldn't read the label") }).run(ByteArray(3), decoded)
        assertEquals("Couldn't read the label", (b.label as OnePhotoCapture.LabelRead.Failed).message)
        assertTrue(b.labelLater)
    }

    @Test fun noBarcodeStillUsesTheLabel() = runBlocking {
        var looked = false
        val c = capture(lookup = { c, _ -> looked = true; found(c) }).run(ByteArray(3), { ScanOutcome.Unreadable })
        assertFalse(looked)
        assertNull(c.barcode)
        assertEquals(listOf(Source.LABEL), c.inputs.map { it.source })
        assertTrue(c.merged.hasNutrition)
    }

    @Test fun fallbackCodeIsUsedWhenThePhotoHasNoBarcode() = runBlocking {
        val c = capture().run(ByteArray(3), { ScanOutcome.Unreadable }, fallbackCode = "0076991000411")
        assertEquals("0076991000411", c.barcode)
        assertTrue(c.inputs.any { it.source == Source.OFF })
    }

    @Test fun unconfirmedCandidatesWaitForAChoiceThenLookUp() = runBlocking {
        val cap = capture()
        val c = cap.run(ByteArray(3), { ScanOutcome.Confirm(listOf(ProductCode("076991000411"), ProductCode("4006381333931"))) })
        assertEquals(2, (c.code as OnePhotoCapture.CodeState.Choose).codes.size)
        assertNull(c.barcode)
        val chosen = cap.withCode(c, ProductCode("076991000411"))
        assertEquals("0076991000411", chosen.barcode)
        assertTrue(chosen.label is OnePhotoCapture.LabelRead.Read)
    }

    @Test fun nothingFoundKeepsTheBarcodeWithNoNutrition() = runBlocking {
        val blank = MealEntity(name = "", kcal = 0, proteinG = 0, carbsG = 0, fatG = 0, barcode = "0076991000411",
            offSnapshotJson = OffSnapshot.failed().encode())
        val c = capture(lookup = { _, _ -> ScanResult.NotFound(blank) }, fdc = { null },
            reader = { label.copy(hasPanel = false, perServing = emptyMap(), ingredientsText = null, productName = "", brand = "") },
        ).run(ByteArray(3), decoded)
        assertEquals("0076991000411", c.barcode)
        assertFalse(c.merged.hasNutrition)
        assertTrue(c.label is OnePhotoCapture.LabelRead.NoPanel)
        assertEquals("", c.suggestedName)
    }

    @Test fun lookupAndFdcErrorsDoNotLoseTheLabel() = runBlocking {
        val c = capture(lookup = { _, _ -> throw java.io.IOException("down") }, fdc = { throw java.io.IOException("down") })
            .run(ByteArray(3), decoded)
        assertEquals("0076991000411", c.barcode)
        assertEquals(listOf(Source.LABEL), c.inputs.map { it.source })
    }

    @Test fun aLiveScanResultJoinsAnExistingCapture() = runBlocking {
        val cap = capture()
        val first = cap.run(ByteArray(3), { ScanOutcome.Unreadable })
        val joined = cap.withScan(first, found("0076991000411"))
        assertEquals("0076991000411", joined.barcode)
        assertTrue(joined.inputs.any { it.source == Source.FDC_BRANDED })
        assertTrue(joined.label is OnePhotoCapture.LabelRead.Read)
    }

    @Test fun aLaterLabelReadReplacesTheSkippedOne() = runBlocking {
        var key = false
        val cap = capture(reader = { if (!key) throw NoApiKey("x") else label })
        val first = cap.run(ByteArray(3), decoded)
        assertTrue(first.labelLater)
        key = true
        val again = cap.withLabel(first, ByteArray(3))
        assertFalse(again.labelLater)
        assertEquals(Source.LABEL, again.merged.per100g.getValue("kcal").source)
        assertEquals(first.barcode, again.barcode)
    }

    @Test fun debugEventsCarryOutcomesNotProductText() = runBlocking {
        val events = mutableListOf<String>()
        capture(events = events).run(ByteArray(3), decoded)
        assertTrue(events.toString(), events.containsAll(listOf("onephoto.decode:decoded", "onephoto.lookup:found", "onephoto.fdc:found", "onephoto.label:read", "onephoto.merge:ok")))
        assertFalse(events.any { "Almond" in it || "0076991000411" in it })
    }
}
