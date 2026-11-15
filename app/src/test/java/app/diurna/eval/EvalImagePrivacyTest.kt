package app.diurna.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Eval JPEGs must be pixels only: a baseline JPEG with a JFIF header and no EXIF/GPS/XMP (APP1),
 * ICC (APP2), IPTC/Photoshop (APP13), other APPn or comment segments. Every .jpg under
 * ai-evals/images is checked, so a new photo can't be added without stripping it.
 */
class EvalImagePrivacyTest {
    private val allowed = setOf(0xE0, 0xDB, 0xC0, 0xC1, 0xC2, 0xC4, 0xDD, 0xDA)

    private fun imagesDir(): File =
        listOf(File("../ai-evals/images"), File("ai-evals/images")).first { it.isDirectory }

    @Test fun everyEvalJpegHasNoMetadataSegments() {
        val files = imagesDir()
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(".jpg", ignoreCase = true) }
            .sortedBy { it.path }
            .toList()
        assertTrue("expected eval JPEGs under ${imagesDir()}", files.size >= 6)
        files.forEach { f ->
            assertEquals(f.name, emptyList<String>(), problems(f.readBytes()))
        }
    }

    @Test fun detectorCatchesExifAndComments() {
        val exif = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(),
            0, 4, 0, 0, 0xFF.toByte(), 0xD9.toByte(),
        )
        assertTrue(problems(exif).any { it.contains("FFE1") })
        val com = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xFE.toByte(),
            0, 4, 65, 66, 0xFF.toByte(), 0xD9.toByte(),
        )
        assertTrue(problems(com).any { it.contains("FFFE") })
    }

    /** Walks JPEG segments; returns anything that isn't an allowed marker. */
    private fun problems(b: ByteArray): List<String> {
        val out = mutableListOf<String>()
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (b.size < 4 || u(0) != 0xFF || u(1) != 0xD8) return listOf("not a JPEG")
        var i = 2
        while (i + 2 <= b.size) {
            if (u(i) != 0xFF) {
                out += "garbage at $i"
                break
            }
            val m = u(i + 1)
            if (m == 0xD9) {
                if (i + 2 != b.size) out += "data after EOI"
                return out
            }
            if (i + 4 > b.size) {
                out += "truncated"
                break
            }
            val len = (u(i + 2) shl 8) or u(i + 3)
            if (m !in allowed) out += "segment FF%02X".format(m)
            if (m == 0xE0 && String(b, i + 4, 4, Charsets.US_ASCII) != "JFIF") out += "APP0 is not JFIF"
            i += 2 + len
            if (m == 0xDA) {
                // Entropy-coded data: skip to the next real marker (not stuffed 00 or RSTn).
                while (i + 1 < b.size && !(u(i) == 0xFF && u(i + 1) != 0 && u(i + 1) !in 0xD0..0xD7)) i++
            }
        }
        if (out.isEmpty()) fail("no EOI")
        return out
    }
}
