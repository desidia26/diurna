package app.diurna.domain

import java.io.File

/**
 * TakePicture scratch-file helpers. The system camera writes into our FileProvider file; some
 * camera apps still return RESULT_CANCELED after a successful write, so callers must not trust
 * the boolean alone.
 */
object PhotoCapture {
    /**
     * Whether to import after [androidx.activity.result.contract.ActivityResultContracts.TakePicture].
     * [captureBytes] is the scratch file's length once the camera returns.
     *
     * A non-empty file wins over a false result: several OEM camera apps write EXTRA_OUTPUT and
     * still return RESULT_CANCELED. A true result with an empty file still imports so the UI can
     * show "Couldn't read that photo" rather than failing silently.
     */
    fun shouldImport(resultOk: Boolean, captureBytes: Long): Boolean =
        captureBytes > 0L || resultOk

    /**
     * Empties [file] (creating parents) so TakePicture has a real destination and a cancelled
     * shot can't reuse a leftover from a previous attempt.
     */
    fun prepareScratch(file: File): File {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(0))
        return file
    }
}
