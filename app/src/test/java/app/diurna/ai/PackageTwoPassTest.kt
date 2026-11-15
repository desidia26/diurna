package app.diurna.ai

import app.diurna.ai.PackageCrops.Box
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageTwoPassTest {
    private val left = Box(0.0, 0.0, 0.45, 1.0)
    private val right = Box(0.55, 0.0, 1.0, 1.0)

    /** Crop bytes say which side: 1 left, 2 right; the whole photo is 0. */
    private class Source(val boxes: List<Box>) : PackageCrops.Source {
        override suspend fun detect() = boxes
        override suspend fun cut(box: Box) = byteArrayOf(if (box.centerX < 0.5) 1 else 2)
    }

    private fun reading(name: String, kcal: Double?) = PackageReading(kcal != null, name, "", "", null, null,
        kcal?.let { mapOf("kcal" to it) } ?: emptyMap(), null, centerX = 0.5)

    private inner class Reader(
        val ids: Map<Int, PackageIdentity?> = mapOf(1 to PackageIdentity("Kroger", "Oats", printedBarcode = "011110766557", centerX = 0.5),
            2 to PackageIdentity("Ocean Spray", "Craisins", centerX = 0.5)),
        val failPass2: Boolean = false,
    ) : PackageReader {
        override val model = "fake"
        val calls = AtomicInteger()
        val knownSeen = mutableMapOf<Int, List<KnownProduct>>()
        val closeUps = mutableListOf<Boolean>()
        override suspend fun read(photo: ByteArray): PackagePhotoReading { calls.incrementAndGet(); return PackagePhotoReading(listOf(reading("whole", 1.0))) }
        override suspend fun refine(photo: ByteArray?, previous: PackagePhotoReading, answer: String) = previous
        override suspend fun identify(photo: ByteArray, closeUp: Boolean): List<PackageIdentity> {
            calls.incrementAndGet()
            synchronized(closeUps) { closeUps += closeUp }
            return listOfNotNull(ids[photo[0].toInt()] ?: error("pass 1 failed"))
        }
        override suspend fun readWithKnown(photo: ByteArray, closeUp: Boolean, known: List<KnownProduct>): PackagePhotoReading {
            calls.incrementAndGet()
            synchronized(knownSeen) { knownSeen[photo[0].toInt()] = known }
            if (failPass2) error("pass 2 failed")
            return PackagePhotoReading(listOf(reading(known.firstOrNull()?.identity?.productName ?: "unknown${photo[0]}", 100.0 + photo[0])))
        }
    }

    private class Lookups : PackageTwoPass.Lookups {
        val asked = mutableListOf<String>()
        override suspend fun byCode(digits: String): List<KnownFacts> {
            synchronized(asked) { asked += "code:$digits" }
            return if (digits.endsWith("766557")) listOf(KnownFacts("usda_branded", "OLD FASHIONED OATS", false, mapOf("kcal" to 375.0), 40.0)) else emptyList()
        }
        override suspend fun byName(query: String): List<KnownFacts> {
            synchronized(asked) { asked += "name:$query" }
            return listOf(KnownFacts("usda_branded", "CRAISINS", true, mapOf("kcal" to 325.0)))
        }
    }

    @Test fun `identity first, then records by barcode or by name, then the label read with them`() = runTest {
        val r = Reader(); val l = Lookups()
        val o = PackageTwoPass.read(Source(listOf(left, right)), byteArrayOf(0), r, l)
        assertEquals(4, r.calls.get())
        assertEquals(4, o.result.calls)
        assertEquals(listOf(true, true), r.closeUps)
        assertEquals(listOf("code:011110766557", "name:Ocean Spray Craisins"), l.asked.sorted())
        val oats = r.knownSeen.getValue(1).single()
        assertEquals("011110766557", oats.barcode)
        assertFalse(oats.facts.single().byName)
        assertTrue(r.knownSeen.getValue(2).single().facts.single().byName)
        assertEquals(listOf("Oats", "Craisins"), o.result.reading.products.map { it.productName })
        assertEquals(listOf(left, right), o.result.origins)
        assertFalse(o.singlePassFallback)
    }

    @Test fun `a barcode the phone decoded inside a package's box is looked up for it`() = runTest {
        val r = Reader(ids = mapOf(1 to PackageIdentity("", "Almonds", centerX = 0.5), 2 to PackageIdentity("", "Pecans", centerX = 0.5)))
        val l = Lookups()
        PackageTwoPass.read(Source(listOf(left, right)), byteArrayOf(0), r, l, decoded = { listOf("0076991000411" to 0.2) })
        assertTrue("code:0076991000411" in l.asked)
        assertEquals("0076991000411", r.knownSeen.getValue(1).single().barcode)
        assertTrue("name:Pecans" in l.asked)
    }

    @Test fun `an invalid printed barcode is not looked up`() = runTest {
        val l = Lookups()
        PackageTwoPass.read(Source(listOf(left, right)), byteArrayOf(0),
            Reader(ids = mapOf(1 to PackageIdentity("A", "B", printedBarcode = "011110766558"), 2 to PackageIdentity("A", "B"))), l)
        assertEquals(listOf("name:A B"), l.asked)
    }

    @Test fun `a failed identity still gets a label read, without records`() = runTest {
        val r = Reader(ids = mapOf(1 to null, 2 to PackageIdentity("Ocean Spray", "Craisins")))
        val o = PackageTwoPass.read(Source(listOf(left, right)), byteArrayOf(0), r, Lookups())
        assertEquals(emptyList<KnownProduct>(), r.knownSeen.getValue(1))
        assertTrue(o.targets[0].identifyFailed)
        assertEquals(2, o.result.reading.products.size)
    }

    @Test fun `every label read failing falls back to the single-pass whole photo`() = runTest {
        val r = Reader(failPass2 = true)
        val o = PackageTwoPass.read(Source(listOf(left, right)), byteArrayOf(0), r, Lookups())
        assertTrue(o.singlePassFallback)
        assertEquals("whole", o.result.reading.products.single().productName)
        assertEquals(5, o.result.calls)
    }

    @Test fun `one package reads the whole photo in two passes`() = runTest {
        val r = Reader(ids = mapOf(0 to PackageIdentity("Kroger", "Oats", printedBarcode = "011110766557")))
        val o = PackageTwoPass.read(Source(listOf(left)), byteArrayOf(0), r, Lookups())
        assertEquals(listOf(false), r.closeUps)
        assertEquals(2, o.result.calls)
        assertEquals(PackageCrops.Fallback.FEW_BOXES, o.result.fallback)
        assertEquals("Oats", o.result.reading.products.single().productName)
    }

    @Test fun `the same name is searched once`() = runTest {
        val l = Lookups()
        PackageTwoPass.read(Source(listOf(left, right)), byteArrayOf(0),
            Reader(ids = mapOf(1 to PackageIdentity("A", "B"), 2 to PackageIdentity("a", "b"))), l)
        assertEquals(1, l.asked.size)
    }

    /** Pass 2 reads a 40 g serving; the oats record is 375 kcal and 6.25 g fat per 100 g, i.e. 150 kcal and 2.5 g. */
    private inner class PanelReader(val panel: Map<Int, Map<String, Double>>) : PackageReader {
        override val model = "fake"
        override suspend fun read(photo: ByteArray) = PackagePhotoReading(emptyList())
        override suspend fun refine(photo: ByteArray?, previous: PackagePhotoReading, answer: String) = previous
        override suspend fun identify(photo: ByteArray, closeUp: Boolean) = listOf(
            if (photo[0].toInt() == 1) PackageIdentity("Kroger", "Oats", printedBarcode = "011110766557", centerX = 0.5)
            else PackageIdentity("Ocean Spray", "Craisins", centerX = 0.5))
        override suspend fun readWithKnown(photo: ByteArray, closeUp: Boolean, known: List<KnownProduct>) = PackagePhotoReading(listOf(
            PackageReading(true, known.first().identity.productName, "", "1/2 cup (40g)", 40.0, null, panel.getValue(photo[0].toInt()), null, centerX = 0.5,
                // A model's own conflict list is ignored: the app compares the numbers itself.
                conflicts = listOf(LabelConflict("kcal", 1.0, 1.0, "made_up")))))
    }

    @Test fun `conflicts are the app's own comparison of the panel with barcode records, per serving`() = runTest {
        val lookups = object : PackageTwoPass.Lookups {
            override suspend fun byCode(digits: String) = listOf(KnownFacts("usda_branded", "OATS", false, mapOf("kcal" to 375.0, "fat_g" to 6.25, "protein_g" to 12.5), 40.0))
            override suspend fun byName(query: String) = listOf(KnownFacts("usda_branded", "CRAISINS", true, mapOf("kcal" to 900.0)))
        }
        val o = PackageTwoPass.read(Source(listOf(left, right)), byteArrayOf(0), PanelReader(mapOf(
            // 152 kcal agrees with 150 (rounding); 4 g fat disagrees with 2.5 g; protein 5 g agrees.
            1 to mapOf("kcal" to 152.0, "fat_g" to 4.0, "protein_g" to 5.0),
            // A name-search record is a guess about which product this is: never compared.
            2 to mapOf("kcal" to 130.0),
        )), lookups)
        val (oats, craisins) = o.result.reading.products
        assertEquals(listOf(LabelConflict("fat_g", 4.0, 2.5, "usda_branded")), oats.conflicts)
        assertTrue(craisins.conflicts.isEmpty())
    }

    @Test fun `no serving weight, nothing to compare`() {
        val p = PackageReading(true, "Oats", "", "1 cup", null, null, mapOf("kcal" to 999.0), null)
        val k = KnownProduct(PackageIdentity("", "Oats"), "011110766557", listOf(KnownFacts("usda_branded", "OATS", false, mapOf("kcal" to 375.0))))
        assertTrue(PackageTwoPass.conflicts(p, k).isEmpty())
    }
}
