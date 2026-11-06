package app.diurna.scan

/**
 * CameraX / ML Kit setup can throw (no camera, in use, driver hiccup). On the alarm screen
 * an uncaught throw would kill the process and silence the ringing service — so every start
 * path must go through this and treat failure as a soft miss the UI can retry.
 */
inline fun <T> tryStartCamera(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(e)
    }
