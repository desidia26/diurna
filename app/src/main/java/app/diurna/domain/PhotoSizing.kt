package app.diurna.domain

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How big a meal photo is kept. One stored size serves the thumbnail, the larger view and both AI
 * providers: 1568 px on the long edge is the largest Claude uses without scaling it down again,
 * and xAI takes anything up to 20 MiB. [MAX_BYTES] keeps the base64 form under Claude's 5 MB cap.
 */
object PhotoSizing {
    const val MAX_EDGE = 1568
    const val MAX_BYTES = 3_500_000

    /** JPEG qualities to try in turn until the file fits [MAX_BYTES]; a 1568 px photo nearly always fits the first. */
    val QUALITIES = listOf(85, 70, 55)

    /** Scales [width]×[height] down (never up) so the long edge is at most [maxEdge], keeping the aspect ratio. */
    fun fit(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "empty image" }
        val long = max(width, height)
        if (long <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / long
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /**
     * Largest power-of-two subsample that still leaves the image at least [target] px on its long
     * edge, for decoding a small thumbnail without loading the full picture.
     */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        val long = max(width, height)
        while (long / (sample * 2) >= target) sample *= 2
        return sample
    }
}
