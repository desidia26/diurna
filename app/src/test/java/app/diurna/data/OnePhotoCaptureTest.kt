package app.diurna.data

import app.diurna.ai.EstimateFailed
import app.diurna.ai.EstimateRetryable
import app.diurna.ai.NoApiKey
import app.diurna.ai.PackageCrops
import app.diurna.ai.PackagePhotoReading
import app.diurna.ai.PackageQuestion
import app.diurna.ai.PackageReader
import app.diurna.ai.PackageReading
import app.diurna.data.LibraryRepository.ScanResult
import app.diurna.data.OnePhotoCapture.LabelStatus
import app.diurna.data.OnePhotoCapture.PhotoCodes
import app.diurna.data.fdc.FdcBrandedFood
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

/** One photo, one or more packages: decode + lookups beside the AI read, matched, merged, logged. */
class OnePhotoCaptureTest {
    private val almondsCode = "0076991000411"
    private val flaxCode = "0860009719569"

    private fun off(name: String, kcal: Int) = """{"status":1,"product":{"product_name":"$name","serving_size":"28 g","serving_quantity":28,
        "nutriments":{"energy-kcal_100g":$kcal,"proteins_100g":21,"carbohydrates_100g":21,"fat_100g":50}}}"""

    private fun found(code: String, name: String = "Almonds", kcal: Int = 600): ScanResult {
        val json = off(name, kcal)
        return ScanResult.Found(parseOffProduct(json)!!.copy(barcode = code, offSnapshotJson = parseOffSnapshot(json, 1L)!!.encode()))
    }

    private fun fdc(gtin: String, desc: String, kcal: Int) = FdcClient.parseBranded(JSONObject("""{"fdcId":${gtin.takeLast(4)},"description":"$desc","gtinUpc":"$gtin",
        "servingSize":28,"servingSizeUnit":"g","ingredients":"X.",
        "foodNutrients":[{"nutrientId":1008,"value":$kcal},{"nutrientId":1003,"value":21.4},{"nutrientId":1005,"value":21.4},{"nutrientId":1004,"value":53.6}]}"""))!!

    private val almonds = PackageReading(true, "Sliced Almonds", "Mariani", "1 oz (28g)", 28.0, 16.0,
        mapOf("kcal" to 170.0, "protein_g" to 6.0, "carbs_g" to 6.0, "fat_g" to 15.0), "Almonds.", centerX = 0.75, printedBarcode = "076991000411")
    private val flax = PackageReading(true, "Flax Seeds", "Roots Circle", "1 Tbsp (7g)", 7.0, 86.0,
        mapOf("kcal" to 35.0, "protein_g" to 1.4, "carbs_g" to 2.1, "fat_g" to 2.8), "FLAXSEEDS", centerX = 0.25)

    private class FakeReader(
        var read: suspend (ByteArray) -> PackagePhotoReading,
        var refine: suspend (ByteArray?, PackagePhotoReading, String) -> PackagePhotoReading = { _, p, _ -> p },
    ) : PackageReader {
        override val model = "fake"
        val refinedWithPhoto = mutableListOf<Boolean>()
        override suspend fun read(photo: ByteArray) = read.invoke(photo)
        override suspend fun refine(photo: ByteArray?, previous: PackagePhotoReading, answer: String): PackagePhotoReading {
            refinedWithPhoto += photo != null
            return refine.invoke(photo, previous, answer)
        }
    }

    private fun capture(
        lookup: suspend (String, Boolean) -> ScanResult = { c, _ -> found(c) },
        fdcByCode: suspend (String) -> FdcBrandedFood? = { fdc("076991000411", "WHOLE ALMONDS", 607) },
        reader: PackageReader? = FakeReader({ PackagePhotoReading(listOf(almonds)) }),
        online: () -> Boolean = { true },
        fdcSearch: suspend (String) -> List<FdcBrandedFood> = { emptyList() },
        offSearch: suspend (String) -> String? = { null },
        events: MutableList<String> = mutableListOf(),
        twoPass: Boolean = true,
    ) = OnePhotoCapture(lookup, fdcByCode, reader, online, fdcSearch, offSearch, twoPass) { name, result, details -> events += "$name:$result:$details" }

    private fun decoded(vararg codes: String): suspend () -> PhotoCodes = {
        if (codes.size == 1) PhotoCodes(ScanOutcome.Decoded(ProductCode(codes[0])))
        else PhotoCodes(ScanOutcome.Confirm(codes.map { ProductCode(it) }), codes.withIndex().associate { (i, c) -> ProductCode(c).key!! to (i + 0.5) / codes.size })
    }

    @Test fun oneProductOneBarcodeMergesWithLabelFirst() = runBlocking {
        val c = capture().run(ByteArray(3), decoded("076991000411"))
        val row = c.rows.single()
        assertEquals(almondsCode, row.code!!.code)
        assertEquals(Source.LABEL, row.merged.per100g.getValue("kcal").source)
        assertEquals(setOf(Source.LABEL, Source.FDC_BRANDED, Source.OFF), row.inputs.map { it.source }.toSet())
        assertEquals("Mariani Sliced Almonds", row.suggestedName)
        assertFalse(c.labelLater)
        assertTrue(c.log.entries.any { it.kind == DecisionLog.SOURCE && "USDA" in it.text })
        assertTrue(c.log.entries.any { it.kind == DecisionLog.MATCH })
        assertEquals(1, c.log.revisions.size)
    }

    @Test fun labelReadRunsWhileTheLookupIsStillGoing() = runBlocking {
        val labelStarted = CompletableDeferred<Unit>()
        val c = capture(
            lookup = { code, _ -> withTimeout(5_000) { labelStarted.await() }; found(code) },
            reader = FakeReader({ labelStarted.complete(Unit); PackagePhotoReading(listOf(almonds)) }),
        ).run(ByteArray(3), decoded("076991000411"))
        assertEquals(1, c.rows.size)
    }

    @Test fun noKeyOfflineAndFailuresKeepTheBarcodeResult() = runBlocking {
        val a = capture(reader = FakeReader({ throw NoApiKey("x") })).run(ByteArray(3), decoded("076991000411"))
        assertEquals("no_key", (a.label as LabelStatus.Skipped).reason)
        assertTrue(a.labelLater)
        assertEquals(Source.FDC_BRANDED, a.rows.single().merged.per100g.getValue("kcal").source)
        var called = false
        val b = capture(reader = FakeReader({ called = true; PackagePhotoReading(listOf(almonds)) }), online = { false }).run(ByteArray(3), decoded("076991000411"))
        assertFalse(called)
        assertEquals("offline", (b.label as LabelStatus.Skipped).reason)
        val d = capture(reader = FakeReader({ throw EstimateRetryable() })).run(ByteArray(3), decoded("076991000411"))
        assertEquals("offline", (d.label as LabelStatus.Skipped).reason)
        val e = capture(reader = FakeReader({ throw EstimateFailed("Couldn't read the label") })).run(ByteArray(3), decoded("076991000411"))
        assertEquals("Couldn't read the label", (e.label as LabelStatus.Failed).message)
        assertEquals("no_key", (capture(reader = null).run(ByteArray(3), decoded("076991000411")).label as LabelStatus.Skipped).reason)
    }

    @Test fun twoPackagesAreMatchedByPrintedDigitsThenPosition() = runBlocking {
        val cap = capture(
            lookup = { c, _ -> if (c.endsWith("0411")) found(c) else found(c, "Flax", 500) },
            fdcByCode = { null },
            reader = FakeReader({ PackagePhotoReading(listOf(flax, almonds)) }),
        )
        // flax barcode on the left, almonds on the right; almonds also has printed digits.
        val c = cap.run(ByteArray(3), decoded("860009719569", "076991000411"))
        assertEquals(2, c.rows.size)
        val f = c.rows.first { it.reading?.productName == "Flax Seeds" }
        val a = c.rows.first { it.reading?.productName == "Sliced Almonds" }
        assertEquals(flaxCode, f.code!!.code)
        assertEquals(almondsCode, a.code!!.code)
        assertTrue(a.matchedBy.contains("printed"))
        assertTrue(f.matchedBy.contains("position"))
    }

    @Test fun anUnmatchedBarcodeBecomesItsOwnRowAndAnUnmatchedProductKeepsItsLabel() = runBlocking {
        val c = capture(fdcByCode = { null }, reader = FakeReader({ PackagePhotoReading(listOf(flax.copy(centerX = null), almonds.copy(centerX = null, printedBarcode = null))) }))
            .run(ByteArray(3), decoded("076991000411", "4006381333931"))
        assertEquals(4, c.rows.size)
        assertEquals(2, c.rows.count { it.reading == null && it.code != null })
        assertTrue(c.rows.filter { it.reading != null }.all { it.code == null && it.merged.hasNutrition })
    }

    @Test fun unverifiedCandidatesWaitForAChoice() = runBlocking {
        val cap = capture()
        val c = cap.run(ByteArray(3), { PhotoCodes(ScanOutcome.Confirm(listOf(ProductCode("076991000411"), ProductCode("4006381333931")))) })
        assertEquals(2, c.choose.size)
        assertTrue(c.rows.single().code == null)
        val chosen = cap.withCode(c, ProductCode("076991000411"))
        assertTrue(chosen.choose.isEmpty())
        assertEquals(almondsCode, chosen.rows.single().code!!.code)
    }

    @Test fun fallbackCodeAndLiveScanJoinTheCapture() = runBlocking {
        val cap = capture()
        val a = cap.run(ByteArray(3), { PhotoCodes(ScanOutcome.Unreadable) }, fallbackCode = almondsCode)
        assertEquals(almondsCode, a.rows.single().code!!.code)
        val b = cap.run(ByteArray(3), { PhotoCodes(ScanOutcome.Unreadable) })
        assertNull(b.rows.single().code)
        val joined = cap.withScan(b, found(almondsCode))
        assertEquals(almondsCode, joined.rows.single().code!!.code)
        assertTrue(joined.rows.single().inputs.any { it.source == Source.FDC_BRANDED })
    }

    @Test fun lookupErrorsDoNotLoseTheLabel() = runBlocking {
        val c = capture(lookup = { _, _ -> throw java.io.IOException("down") }, fdcByCode = { throw java.io.IOException("down") })
            .run(ByteArray(3), decoded("076991000411"))
        assertEquals(listOf(Source.LABEL), c.rows.single().inputs.map { it.source })
    }

    @Test fun aFrontOnlyProductGetsGuessesThatAreNeverAutoAccepted() = runBlocking {
        val front = PackageReading(false, "Flax Seeds", "Roots Circle", "", null, null, emptyMap(), null, sizeText = "16 OZ")
        val queries = mutableListOf<String>()
        val offAnswer = """{"products":[{"code":"0860009719569","product_name":"Flax seeds","brands":"Roots Circle","serving_quantity":7,
            "nutriments":{"energy-kcal_100g":534,"proteins_100g":18,"carbohydrates_100g":29,"fat_100g":42}}]}"""
        val cap = capture(
            reader = FakeReader({ PackagePhotoReading(listOf(front)) }),
            fdcSearch = { q -> queries += q; listOf(fdc("860009719569", "FLAX SEEDS", 530)) },
            offSearch = { q -> queries += q; offAnswer },
        )
        val c = cap.run(ByteArray(3), { PhotoCodes(ScanOutcome.Unreadable) })
        val row = c.rows.single()
        assertEquals(listOf("Roots Circle Flax Seeds", "Roots Circle Flax Seeds"), queries)
        assertEquals(2, row.candidates.size)
        assertNull(row.picked)
        assertFalse("identity alone never becomes numbers", row.merged.hasNutrition)
        assertTrue(c.log.entries.any { it.kind == DecisionLog.GUESS })
        val picked = cap.pick(c, row.key, row.candidates.first { it.source == Source.OFF }.ref)
        val p = picked.rows.single()
        assertEquals(Source.OFF, p.merged.per100g.getValue("kcal").source)
        assertTrue(p.isGuess)
        assertTrue(picked.log.entries.last().text.contains("confirmed", ignoreCase = true))
        val none = cap.pick(picked, row.key, null)
        assertFalse(none.rows.single().merged.hasNutrition)
    }

    @Test fun noCandidatesMeansNoNumbers() = runBlocking {
        val front = PackageReading(false, "Mystery Mix", "", "", null, null, emptyMap(), null)
        val c = capture(reader = FakeReader({ PackagePhotoReading(listOf(front)) })).run(ByteArray(3), { PhotoCodes(ScanOutcome.Unreadable) })
        assertTrue(c.rows.single().candidates.isEmpty())
        assertFalse(c.rows.single().merged.hasNutrition)
    }

    @Test fun questionsAreLoggedAndARefinementAddsARevisionKeepingTheOriginal() = runBlocking {
        val q = PackageQuestion(0, "serving_basis", "Dry or prepared column?", listOf("Dry", "Prepared"))
        val prepared = flax.copy(perServing = flax.perServing + ("kcal" to 45.0))
        val reader = FakeReader({ PackagePhotoReading(listOf(flax), listOf(q)) }, { _, _, _ -> PackagePhotoReading(listOf(prepared)) })
        val cap = capture(reader = reader, fdcByCode = { null })
        val c = cap.run(ByteArray(3), { PhotoCodes(ScanOutcome.Unreadable) })
        assertEquals(listOf(q), c.questions)
        assertTrue(c.log.entries.any { it.kind == DecisionLog.QUESTION && "Dry or prepared" in it.text })
        val r = cap.refine(c, null, "Prepared")
        assertEquals(listOf(false), reader.refinedWithPhoto) // the photo was gone: text only
        assertEquals(2, r.log.revisions.size)
        assertEquals(flax, r.log.revisions[0].reading.products.single())
        assertEquals(45.0 / 7 * 100, r.rows.single().merged.per100g.getValue("kcal").value, 0.01)
        assertTrue(r.questions.isEmpty())
        assertTrue(r.log.entries.any { it.kind == DecisionLog.ANSWER && "Prepared" in it.text })
        val back = cap.revert(r, 0)
        assertEquals(35.0 / 7 * 100, back.rows.single().merged.per100g.getValue("kcal").value, 0.01)
        assertEquals(2, back.log.revisions.size)
    }

    @Test fun aFailedRefinementKeepsTheCurrentInterpretation() = runBlocking {
        val reader = FakeReader({ PackagePhotoReading(listOf(flax)) }, { _, _, _ -> throw EstimateFailed("nope") })
        val cap = capture(reader = reader, fdcByCode = { null })
        val c = cap.run(ByteArray(3), { PhotoCodes(ScanOutcome.Unreadable) })
        val r = cap.refine(c, ByteArray(2), "x")
        assertEquals(1, r.log.revisions.size)
        assertEquals("nope", r.problem)
    }

    @Test fun aLaterLabelReadBecomesTheFirstInterpretation() = runBlocking {
        var key = false
        val cap = capture(reader = FakeReader({ if (!key) throw NoApiKey("x") else PackagePhotoReading(listOf(almonds)) }))
        val first = cap.run(ByteArray(3), decoded("076991000411"))
        assertTrue(first.labelLater)
        key = true
        val again = cap.withLabel(first, ByteArray(3))
        assertFalse(again.labelLater)
        assertEquals(Source.LABEL, again.rows.single().merged.per100g.getValue("kcal").source)
        assertEquals(almondsCode, again.rows.single().code!!.code)
    }

    @Test fun debugEventsCarryOutcomesNotProductTextOrCodes() = runBlocking {
        val events = mutableListOf<String>()
        capture(events = events).run(ByteArray(3), decoded("076991000411"))
        val names = events.map { it.substringBefore(":{") }
        assertTrue(names.toString(), names.containsAll(listOf("onephoto.decode:decoded", "onephoto.lookup:found", "onephoto.fdc:found", "onephoto.label:read", "onephoto.merge:ok")))
        assertFalse(events.any { "Almond" in it || "0076991000411" in it || "076991000411" in it })
    }

    private class Crops(val boxes: List<PackageCrops.Box>) : PackageCrops.Source {
        override suspend fun detect() = boxes
        // The crop's bytes say which box it is, so the fake reader can answer per package.
        override suspend fun cut(box: PackageCrops.Box) = byteArrayOf(if (box.left < 0.5) 1 else 2)
    }
    private val twoBoxes = listOf(PackageCrops.Box(0.05, 0.1, 0.45, 0.9), PackageCrops.Box(0.55, 0.1, 0.95, 0.9))

    @Test fun severalPackagesAreReadFromTheirOwnCropsAndPairWithBarcodesByPosition() = runBlocking {
        val sent = mutableListOf<Int>()
        val events = mutableListOf<String>()
        val reader = FakeReader({ b -> synchronized(sent) { sent += b[0].toInt() }
            when (b[0].toInt()) {
                1 -> PackagePhotoReading(listOf(flax.copy(centerX = 0.5)))
                2 -> PackagePhotoReading(listOf(almonds.copy(centerX = 0.5)))
                else -> error("whole photo should not be read")
            } })
        val c = capture(reader = reader, events = events, twoPass = false, lookup = { code, _ -> found(code, if (code.endsWith("411")) "Almonds" else "Flax", 600) })
            .run(byteArrayOf(0), decoded(flaxCode, almondsCode), crops = Crops(twoBoxes))
        assertEquals(listOf(1, 2), sent.sorted())
        assertEquals(2, c.rows.size)
        assertTrue(c.log.entries.any { it.text.startsWith("Found 2 packages on the phone") })
        assertEquals(listOf(0, 1), c.log.entries.filter { it.text.contains("read from its own close-up crop") }.map { it.product })
        assertTrue(events.any { it.startsWith("onephoto.crops:cropped:") && "calls=2" in it })
    }

    @Test fun onePackageFoundReadsTheWholePhoto() = runBlocking {
        val sent = mutableListOf<Int>()
        val c = capture(reader = FakeReader({ b -> sent += b[0].toInt(); PackagePhotoReading(listOf(almonds)) }), twoPass = false)
            .run(byteArrayOf(0), decoded(almondsCode), crops = Crops(twoBoxes.take(1)))
        assertEquals(listOf(0), sent)
        assertEquals(1, c.rows.size)
        assertTrue(c.log.entries.any { it.text.contains("read the photo whole") })
        assertTrue(c.log.entries.none { it.text.contains("own close-up crop") })
    }

    @Test fun noKeyStillSkipsTheLabelWithCrops() = runBlocking {
        val c = capture(reader = FakeReader({ throw NoApiKey("no key") })).run(byteArrayOf(0), decoded(almondsCode), crops = Crops(twoBoxes))
        assertTrue(c.label is LabelStatus.Skipped)
    }

    /** Pass 1 names each package, the lookups find records, pass 2 reads with them: all of it in the log. */
    private inner class TwoPassReader : PackageReader {
        override val model = "fake"
        val known = mutableMapOf<Int, List<app.diurna.ai.KnownProduct>>()
        var whole = 0
        override suspend fun read(photo: ByteArray): PackagePhotoReading { whole++; return PackagePhotoReading(listOf(almonds)) }
        override suspend fun refine(photo: ByteArray?, previous: PackagePhotoReading, answer: String) = previous
        override suspend fun identify(photo: ByteArray, closeUp: Boolean) = listOf(
            if (photo[0].toInt() == 1) app.diurna.ai.PackageIdentity("Roots Circle", "Flax Seeds", centerX = 0.5)
            else app.diurna.ai.PackageIdentity("Mariani", "Sliced Almonds", printedBarcode = "076991000411", centerX = 0.5))
        override suspend fun readWithKnown(photo: ByteArray, closeUp: Boolean, known: List<app.diurna.ai.KnownProduct>): PackagePhotoReading {
            synchronized(this.known) { this.known[photo[0].toInt()] = known }
            return if (photo[0].toInt() == 1) PackagePhotoReading(listOf(flax.copy(hasPanel = false, perServing = emptyMap(), centerX = 0.5,
                guessed = mapOf("fiber_g" to 2.0))))
            else PackagePhotoReading(listOf(almonds.copy(centerX = 0.5, perServing = almonds.perServing + ("kcal" to 300.0),
                // Ignored: the app compares the panel with the records itself.
                conflicts = listOf(app.diurna.ai.LabelConflict("fat_g", 1.0, 2.0, "made_up")))))
        }
    }

    @Test fun twoPassReadsEachPackageWithItsRecordsAndLogsEveryStep() = runBlocking {
        val r = TwoPassReader()
        val events = mutableListOf<String>()
        val searched = mutableListOf<String>()
        val lookedUp = mutableListOf<String>()
        val c = capture(reader = r, events = events, fdcSearch = { q -> searched += q; emptyList() },
            lookup = { code, _ -> synchronized(lookedUp) { lookedUp += code }; found(code) })
            .run(byteArrayOf(0), decoded(almondsCode), crops = Crops(twoBoxes))
        assertEquals(0, r.whole)
        // The almonds' printed barcode is looked up once, for pass 2 and the merge alike.
        assertEquals(1, lookedUp.count { normalizeBarcode(it) == normalizeBarcode(almondsCode) })
        assertEquals("usda_branded", r.known.getValue(2).single().facts.first().source)
        assertEquals(listOf("Roots Circle Flax Seeds"), searched)
        val text = c.log.entries.map { it.text }
        assertTrue(text.any { it.startsWith("Pass 1 (identity) for package 1") && "Flax Seeds" in it })
        assertTrue(text.any { it.startsWith("Lookup by barcode 076991000411") })
        assertTrue(text.any { it.startsWith("Lookup by name “Roots Circle Flax Seeds”: no database record") })
        assertTrue(text.any { it.startsWith("AI guesses for product 1") && "fiber_g 2" in it })
        assertTrue(text.any { it.startsWith("Label and usda_branded disagree on kcal for product 2: label 300") })
        assertTrue(text.any { it.startsWith("Label and open_food_facts disagree on kcal for product 2: label 300") })
        assertTrue(text.none { "made_up" in it })
        assertTrue(events.any { it.startsWith("onephoto.two_pass:ok:") && "guessed=1" in it && "conflicts=2" in it })
        assertEquals(2, c.rows.size)
    }
}
