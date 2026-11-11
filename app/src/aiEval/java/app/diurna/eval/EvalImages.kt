package app.diurna.eval

import app.diurna.ai.PackageCrops
import com.fasterxml.jackson.databind.ObjectMapper
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * What the phone does to a photo before the AI sees it, on the JVM: scale the whole photo to
 * [app.diurna.domain.PhotoSizing.MAX_EDGE] as PhotoImporter does, or cut one package's box out of
 * the full-resolution photo at that size as [app.diurna.data.PackageCrop] does (both use
 * [PackageCrops.cropTarget]). Eval fixtures carry no EXIF orientation.
 */
object EvalImages {
    private fun decode(jpeg: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(jpeg)) ?: error("not an image")

    private fun encode(img: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()

    private fun scaled(src: BufferedImage, l: Int, t: Int, r: Int, b: Int, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(src, 0, 0, w, h, l, t, r, b, null)
        g.dispose()
        return out
    }

    /** The photo as sent for a whole-photo read: unchanged if it already fits. */
    fun fit(jpeg: ByteArray, maxEdge: Int = app.diurna.domain.PhotoSizing.MAX_EDGE): ByteArray {
        val img = ImageIO.read(ByteArrayInputStream(jpeg)) ?: return jpeg
        val (w, h) = app.diurna.domain.PhotoSizing.fit(img.width, img.height, maxEdge)
        if (w == img.width && h == img.height) return jpeg
        return encode(scaled(img, 0, 0, img.width, img.height, w, h))
    }

    /** One package's close-up: the phone decodes at the target size, then crops; here crop at full size, then scale to the same pixels. */
    fun crop(jpeg: ByteArray, box: PackageCrops.Box, maxEdge: Int = app.diurna.domain.PhotoSizing.MAX_EDGE): ByteArray {
        val img = decode(jpeg)
        val t = PackageCrops.cropTarget(img.width, img.height, box, maxEdge)
        val sx = img.width.toDouble() / t[0]
        val sy = img.height.toDouble() / t[1]
        val l = (t[2] * sx).toInt(); val tp = (t[3] * sy).toInt()
        val r = (t[4] * sx).toInt().coerceIn(l + 1, img.width); val b = (t[5] * sy).toInt().coerceIn(tp + 1, img.height)
        return encode(scaled(img, l, tp, r, b, t[4] - t[2], t[5] - t[3]))
    }
}

/**
 * The package finder's boxes for each fixture (ai-evals/detections.json): ML Kit's detector can't
 * run on the JVM, so its bundled model's boxes are recorded per fixture and the harness crops
 * from them, through the same [PackageCrops] plan, padding, sizing and merge as the phone.
 */
class EvalDetections(file: File) {
    private val images: Map<String, List<PackageCrops.Box>> = ObjectMapper().readTree(file)["images"].fields().asSequence().associate { (k, v) ->
        k to v.map { d -> d["box"].map { it.asDouble() }.let { b -> PackageCrops.Box(b[0], b[1], b[2], b[3]) } }
    }

    fun has(image: String) = image in images

    /** A [PackageCrops.Source] for [image] (a path relative to ai-evals) whose bytes are [photo]. */
    fun source(image: String, photo: ByteArray): PackageCrops.Source = object : PackageCrops.Source {
        override suspend fun detect() = images[image] ?: error("no recorded detections for $image")
        override suspend fun cut(box: PackageCrops.Box) = EvalImages.crop(photo, box)
    }
}
