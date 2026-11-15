package app.diurna.eval

import app.diurna.ai.PackageCrops
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recorded package-finder boxes cover every package case and crop as the phone would. */
class EvalDetectionsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "ai-evals/detections.json").isFile }
    private val evals = File(root, "ai-evals")
    private val detections = EvalDetections(File(evals, "detections.json"))
    private val set = EvalCases.load(File(evals, "cases"), "default")

    private fun plan(image: String) = runBlocking { PackageCrops.plan(detections.source(image, ByteArray(0)).detect()) }

    @Test fun `every package case's photo has recorded boxes`() {
        val images = set.cases.filter { it.prompt == "package" }.mapNotNull { it.input.image }
        assertTrue(images.isNotEmpty())
        images.forEach { assertTrue("no detections for $it", detections.has(it)) }
    }

    @Test fun `multi-package photos are cropped and single packages read whole`() {
        assertEquals(5, plan("images/real/oatmeal-six.jpg")?.size)
        assertEquals(3, plan("images/real/montage-pecans-almonds-flax.jpg")?.size)
        set.cases.filter { it.prompt == "package" && "multi" !in it.tags }.mapNotNull { it.input.image }.forEach {
            assertNull("$it should be read whole", plan(it))
        }
    }

    @Test fun `a crop is cut at full resolution and never upscaled`() {
        val photo = File(evals, "images/real/oatmeal-six.jpg").readBytes()
        val box = plan("images/real/oatmeal-six.jpg")!!.first().padded()
        val img = ImageIO.read(EvalImages.crop(photo, box).inputStream())
        val src = ImageIO.read(photo.inputStream())
        assertEquals((box.width * src.width).toDouble(), img.width.toDouble(), 2.0)
        assertEquals((box.height * src.height).toDouble(), img.height.toDouble(), 2.0)
    }
}
