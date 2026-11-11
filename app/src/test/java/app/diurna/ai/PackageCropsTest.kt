package app.diurna.ai

import app.diurna.ai.PackageCrops.Box
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageCropsTest {
    private fun box(l: Double, t: Double, r: Double, b: Double) = Box(l, t, r, b)

    // The bundled ML Kit localizer's boxes for the eval fixtures (ai-evals/detections.json).
    private val six = listOf(box(0.532, 0.0, 0.803, 0.83), box(0.064, 0.0, 0.385, 0.857), box(0.713, 0.06, 0.996, 0.856),
        box(0.323, 0.328, 0.568, 0.888), box(0.0, 0.442, 0.218, 0.995))
    private val montage = listOf(box(0.006, 0.501, 0.487, 0.994), box(0.526, 0.013, 0.979, 0.473), box(0.064, 0.006, 0.429, 0.492))

    @Test fun `six packages on a counter become five crops, left to right`() {
        val plan = PackageCrops.plan(six)!!
        assertEquals(5, plan.size)
        assertEquals(listOf(0.0, 0.064, 0.323, 0.532, 0.713), plan.map { it.left })
    }

    @Test fun `montage cells become three crops in reading order`() {
        val plan = PackageCrops.plan(montage)!!
        assertEquals(3, plan.size)
        // Top row first, then bottom.
        assertEquals(listOf(0.064, 0.526, 0.006), plan.map { it.left })
    }

    @Test fun `one package, or one with a detail box inside it, falls back to the whole photo`() {
        assertNull(PackageCrops.plan(listOf(box(0.026, 0.028, 0.941, 0.982))))
        assertNull(PackageCrops.plan(listOf(box(0.023, 0.037, 0.977, 0.98), box(0.625, 0.779, 0.944, 0.976))))
        assertNull(PackageCrops.plan(emptyList()))
    }

    @Test fun `a box around two packages is dropped and the packages kept`() {
        val group = box(0.05, 0.0, 0.6, 0.9)
        val plan = PackageCrops.plan(listOf(group, box(0.064, 0.0, 0.385, 0.857), box(0.323, 0.328, 0.568, 0.888)))!!
        assertEquals(2, plan.size)
        assertTrue(group !in plan)
    }

    @Test fun `near-duplicate boxes keep one and tiny boxes are ignored`() {
        val a = box(0.1, 0.1, 0.4, 0.9)
        val plan = PackageCrops.plan(listOf(a, box(0.11, 0.12, 0.41, 0.88), box(0.6, 0.1, 0.9, 0.9), box(0.95, 0.95, 0.99, 0.99)))!!
        assertEquals(2, plan.size)
        assertTrue(plan.all { it.area >= PackageCrops.MIN_AREA })
    }

    @Test fun `boxes are clamped to the photo and the crop count is capped`() {
        val many = (0 until 8).map { i -> box(i * 0.125 - 0.01, -0.05, i * 0.125 + 0.12, 1.02) }
        val plan = PackageCrops.plan(many)!!
        assertEquals(PackageCrops.MAX_CROPS, plan.size)
        assertTrue(plan.all { it.left >= 0 && it.top >= 0 && it.right <= 1 && it.bottom <= 1 })
    }

    @Test fun `padding is modest and stays inside the photo`() {
        val p = box(0.0, 0.442, 0.218, 0.995).padded()
        assertEquals(0.0, p.left, 1e-9)
        assertEquals(1.0, p.bottom, 1e-9)
        assertEquals(0.218 + 0.218 * PackageCrops.PAD, p.right, 1e-9)
        // A neighbour 5% of the photo away stays out of the crop.
        assertTrue(box(0.3, 0.1, 0.5, 0.9).padded().right < 0.55)
    }

    private fun product(name: String, kcal: Double? = null, x: Double? = 0.5, panel: Boolean = kcal != null, code: String? = null) =
        PackageReading(panel, name, "", "", null, null, kcal?.let { mapOf("kcal" to it) } ?: emptyMap(), null, centerX = x, printedBarcode = code)

    @Test fun `merge takes the package at each crop's centre and places it at the box`() {
        val a = box(0.0, 0.0, 0.3, 1.0); val b = box(0.4, 0.0, 0.7, 1.0)
        val m = PackageCrops.merge(listOf(
            a to PackagePhotoReading(listOf(product("edge of neighbour", 90.0, 0.95), product("oats", 150.0, 0.45))),
            b to PackagePhotoReading(listOf(product("almonds", 170.0, 0.5))),
        ))
        assertEquals(listOf("oats", "almonds"), m.reading.products.map { it.productName })
        assertEquals(listOf(0.15, 0.55), m.reading.products.map { it.centerX!! }.map { Math.round(it * 100) / 100.0 })
        assertEquals(listOf(a, b), m.origins)
    }

    @Test fun `the same product read from two crops is one row, keeping the richer read`() {
        val m = PackageCrops.merge(listOf(
            box(0.0, 0.0, 0.3, 1.0) to PackagePhotoReading(listOf(product("Pecan Halves"))),
            box(0.3, 0.0, 0.6, 1.0) to PackagePhotoReading(listOf(product("pecan halves", 200.0))),
            box(0.6, 0.0, 0.9, 1.0) to PackagePhotoReading(listOf(product("oats", 150.0))),
        ))
        assertEquals(listOf("pecan halves", "oats"), m.reading.products.map { it.productName })
        assertEquals(200.0, m.reading.products[0].perServing["kcal"])
    }

    @Test fun `two packages of one product that read different panels stay separate`() {
        val m = PackageCrops.merge(listOf(
            box(0.0, 0.0, 0.3, 1.0) to PackagePhotoReading(listOf(product("granola", 120.0))),
            box(0.4, 0.0, 0.7, 1.0) to PackagePhotoReading(listOf(product("granola", 240.0))),
        ))
        assertEquals(2, m.reading.products.size)
    }

    @Test fun `questions follow their product and failed crops are skipped`() {
        val m = PackageCrops.merge(listOf(
            box(0.0, 0.0, 0.3, 1.0) to null,
            box(0.4, 0.0, 0.7, 1.0) to PackagePhotoReading(listOf(product("rice", 160.0)), listOf(PackageQuestion(0, "serving", "Cooked or dry?"))),
            box(0.7, 0.0, 1.0, 1.0) to PackagePhotoReading(emptyList()),
        ))
        assertEquals(listOf("rice"), m.reading.products.map { it.productName })
        assertEquals(listOf(0), m.reading.questions.map { it.productIndex })
    }

    private class FakeSource(val boxes: List<Box>?, val fail: Set<Int> = emptySet()) : PackageCrops.Source {
        val cuts = AtomicInteger()
        override suspend fun detect(): List<Box> = boxes ?: error("detector unavailable")
        override suspend fun cut(box: Box): ByteArray? {
            cuts.incrementAndGet()
            return byteArrayOf((box.left * 100).toInt().toByte())
        }
    }

    @Test fun `read sends each crop once, in parallel up to the cap`() = runTest {
        val src = FakeSource(six)
        val inFlight = AtomicInteger(); var peak = 0; val calls = AtomicInteger()
        val out = PackageCrops.read(src, byteArrayOf(-1)) { bytes ->
            calls.incrementAndGet()
            peak = maxOf(peak, inFlight.incrementAndGet())
            delay(100)
            inFlight.decrementAndGet()
            PackagePhotoReading(listOf(product("p${bytes[0]}", 100.0 + bytes[0])))
        }
        assertEquals(5, calls.get())
        assertTrue(peak in 2..PackageCrops.MAX_PARALLEL)
        assertEquals(5, out.reading.products.size)
        assertNull(out.fallback)
        assertEquals(5, out.crops.size)
        assertEquals(5, out.read)
    }

    @Test fun `few boxes, a detector failure, or every crop failing reads the whole photo`() = runTest {
        val whole = PackagePhotoReading(listOf(product("whole", 1.0)))
        suspend fun run(src: PackageCrops.Source, failCrops: Boolean = false) = PackageCrops.read(src, byteArrayOf(-1)) { bytes ->
            if (bytes[0] == (-1).toByte()) whole else if (failCrops) error("crop read failed") else PackagePhotoReading(listOf(product("crop", 2.0)))
        }
        assertEquals(PackageCrops.Fallback.FEW_BOXES, run(FakeSource(listOf(box(0.1, 0.1, 0.9, 0.9)))).fallback)
        assertEquals(PackageCrops.Fallback.DETECTOR_FAILED, run(FakeSource(null)).fallback)
        val all = run(FakeSource(montage), failCrops = true)
        assertEquals(PackageCrops.Fallback.CROPS_FAILED, all.fallback)
        assertEquals(whole, all.reading)
        assertEquals(3, all.crops.size)
    }

    @Test fun `crop target never upscales and keeps the long side within the limit`() {
        val t = PackageCrops.cropTarget(4000, 3000, box(0.5, 0.0, 1.0, 1.0), 1568)
        assertEquals(1568, maxOf(t[4] - t[2], t[5] - t[3]))
        val small = PackageCrops.cropTarget(1000, 800, box(0.0, 0.0, 0.5, 0.5), 1568)
        assertEquals(listOf(1000, 800, 0, 0, 500, 400), small.toList())
    }
}
