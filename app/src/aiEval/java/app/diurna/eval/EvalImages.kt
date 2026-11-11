package app.diurna.eval

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * What the phone does to a photo before the AI sees it, on the JVM: scale it so its long side is
 * at most [app.diurna.domain.PhotoSizing.MAX_EDGE], as PhotoImporter does. A fixture can then be
 * kept at the resolution it was taken at. Eval fixtures carry no EXIF orientation.
 */
object EvalImages {
    /** The photo as sent to the AI: unchanged if it already fits. */
    fun fit(jpeg: ByteArray, maxEdge: Int = app.diurna.domain.PhotoSizing.MAX_EDGE): ByteArray {
        val img = ImageIO.read(ByteArrayInputStream(jpeg)) ?: return jpeg
        val (w, h) = app.diurna.domain.PhotoSizing.fit(img.width, img.height, maxEdge)
        if (w == img.width && h == img.height) return jpeg
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(img, 0, 0, w, h, 0, 0, img.width, img.height, null)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(out, "jpg", it) }.toByteArray()
    }
}
