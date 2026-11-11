package app.diurna.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import app.diurna.ai.PackageCrops
import app.diurna.domain.PhotoSizing
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One package cut out of the full-resolution camera photo, for its close-up read. In memory only:
 * nothing is written to disk, and the JPEG carries no metadata. One decode at a time, since the
 * crop of a small package can need the whole photo decoded at full size.
 */
object PackageCrop {
    private val one = Mutex()

    /** [box] (fractions of the upright photo) as a JPEG with its long side at most [PhotoSizing.MAX_EDGE]; null if it can't be cut. */
    suspend fun cut(file: File, box: PackageCrops.Box): ByteArray? = withContext(Dispatchers.IO) {
        one.withLock {
            runCatching {
                // ImageDecoder applies the EXIF orientation, so the box (found in the upright photo) lines up.
                val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val t = PackageCrops.cropTarget(info.size.width, info.size.height, box, PhotoSizing.MAX_EDGE)
                    decoder.setTargetSize(t[0], t[1])
                    decoder.crop = Rect(t[2], t[3], t[4], t[5])
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setOnPartialImageListener { false }
                }
                try { compress(bitmap) } finally { bitmap.recycle() }
            }.getOrNull()
        }
    }

    private fun compress(bitmap: Bitmap): ByteArray? {
        for (quality in PhotoSizing.QUALITIES) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= PhotoSizing.MAX_BYTES) return out.toByteArray()
        }
        return null
    }
}
