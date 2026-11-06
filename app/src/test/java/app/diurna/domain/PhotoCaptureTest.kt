package app.diurna.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression: logging cardio (or food) from "Take photo" wrote the JPEG but never imported it
 * when the camera app returned RESULT_CANCELED after a successful EXTRA_OUTPUT write. Gallery
 * picks were fine because they use a different contract.
 */
class PhotoCaptureTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun importsWhenCameraWroteTheFileEvenIfResultIsCanceled() {
        // The bug: trusting only the boolean skipped a real capture (false + non-empty file).
        assertTrue(PhotoCapture.shouldImport(resultOk = false, captureBytes = 12_345L))
    }

    @Test fun importsWhenResultOk() {
        assertTrue(PhotoCapture.shouldImport(resultOk = true, captureBytes = 12_345L))
        // RESULT_OK with an empty file still tries import so the UI can show "Couldn't read…".
        assertTrue(PhotoCapture.shouldImport(resultOk = true, captureBytes = 0L))
    }

    @Test fun ignoresACanceledShotWithAnEmptyScratchFile() {
        assertFalse(PhotoCapture.shouldImport(resultOk = false, captureBytes = 0L))
    }

    @Test fun prepareScratchCreatesAnEmptyFileSoLeftoversCantBeReused() {
        val dir = tmp.newFolder("camera")
        val file = File(dir, "capture.jpg")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        PhotoCapture.prepareScratch(file)
        assertTrue(file.isFile)
        assertEquals(0, file.length())
        val nested = File(tmp.root, "new/camera/capture.jpg")
        PhotoCapture.prepareScratch(nested)
        assertTrue(nested.isFile)
        assertEquals(0, nested.length())
    }
}
