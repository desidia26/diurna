package app.diurna.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import app.diurna.domain.PhotoSizing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns a camera shot or a picked picture into a stored meal photo: decoded straight to at most
 * [PhotoSizing.MAX_EDGE] (so a huge or hostile image never loads at full size), turned upright from
 * its EXIF orientation by [ImageDecoder], and saved as a JPEG under [PhotoSizing.MAX_BYTES].
 */
class PhotoImporter(private val context: Context, private val photos: FoodPhotos) {

    /** Where the camera app writes its shot; shared with it through the FileProvider in the manifest. */
    val captureFile: File get() = File(context.cacheDir, "camera/capture.jpg").apply { parentFile?.mkdirs() }

    /** A picture from the photo picker. The new photo's name, or null if it couldn't be read. */
    suspend fun import(uri: Uri): String? = import(ImageDecoder.createSource(context.contentResolver, uri))

    /** The camera's shot in [captureFile], which is then removed. */
    suspend fun importCapture(): String? =
        import(ImageDecoder.createSource(captureFile)).also { withContext(Dispatchers.IO) { captureFile.delete() } }

    private suspend fun import(source: ImageDecoder.Source): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (w, h) = PhotoSizing.fit(info.size.width, info.size.height)
                decoder.setTargetSize(w, h)
                // Software memory, so it can be compressed; and no partial images from a truncated file.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
            val bytes = try { compress(bitmap) } finally { bitmap.recycle() } ?: return@runCatching null
            photos.dir.mkdirs()
            val name = photos.newName()
            File(photos.dir, name).writeBytes(bytes)
            name
        }.onFailure { Log.w(TAG, "couldn't import photo", it) }.getOrNull()
    }

    private fun compress(bitmap: Bitmap): ByteArray? {
        for (quality in PhotoSizing.QUALITIES) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= PhotoSizing.MAX_BYTES) return out.toByteArray()
        }
        return null
    }

    private companion object {
        const val TAG = "DiurnaPhoto"
    }
}
