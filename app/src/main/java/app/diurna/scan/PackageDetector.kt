package app.diurna.scan

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import app.diurna.ai.PackageCrops
import app.diurna.data.PackageCrop
import app.diurna.domain.PhotoSizing
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Where each package is in a photo, from ML Kit's object detector with its bundled base model
 * (the localizer ships inside the app: no download, no network, nothing leaves the phone). It
 * finds at most five objects and doesn't name them; [PackageCrops.plan] decides which are packages.
 */
object PackageDetector {
    /** The detector works on a 192 px input; a 1024 px decode is plenty and keeps memory low. */
    const val DETECT_EDGE = 1024
    /** A phone takes well under a second; the timeout only guards against a wedged detector. */
    const val TIMEOUT_MS = 10_000L

    /** Boxes as fractions of the upright photo (ImageDecoder applies the EXIF orientation). */
    suspend fun detect(file: File, timeoutMs: Long = TIMEOUT_MS): List<PackageCrops.Box> = withContext(Dispatchers.IO) {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val (w, h) = PhotoSizing.fit(info.size.width, info.size.height, DETECT_EDGE)
                decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
            }
        } catch (e: Exception) { throw IOException("invalid photo", e) }
        withTimeout(timeoutMs) { detect(bitmap) }
    }

    /** Takes ownership of [bitmap] and recycles it when the detector is done with it. */
    suspend fun detect(bitmap: Bitmap): List<PackageCrops.Box> {
        val w = bitmap.width.toDouble(); val h = bitmap.height.toDouble()
        val detector = ObjectDetection.getClient(ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE).enableMultipleObjects().build())
        return try {
            suspendCancellableCoroutine { continuation ->
                try {
                    detector.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                        // ML Kit can finish after the caller gave up; keep its input alive until then.
                        bitmap.recycle()
                        if (continuation.isActive) {
                            if (task.isSuccessful) continuation.resume(task.result.map { o ->
                                val r = o.boundingBox
                                PackageCrops.Box(r.left / w, r.top / h, r.right / w, r.bottom / h)
                            }) else continuation.resumeWithException(task.exception ?: IOException("detection failed"))
                        }
                    }
                } catch (e: Exception) {
                    bitmap.recycle()
                    continuation.resumeWithException(e)
                }
            }
        } finally { detector.close() }
    }
}

/** The packages in a private full-resolution photo file: detected on the phone and cut out in memory. */
class PhotoPackageSource(
    private val file: File,
    private val timeoutMs: Long = PackageDetector.TIMEOUT_MS,
) : PackageCrops.Source {
    override suspend fun detect(): List<PackageCrops.Box> = PackageDetector.detect(file, timeoutMs)
    override suspend fun cut(box: PackageCrops.Box): ByteArray? = PackageCrop.cut(file, box)
}
