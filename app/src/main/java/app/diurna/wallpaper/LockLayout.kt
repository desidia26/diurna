package app.diurna.wallpaper

/**
 * The band of the screen, as fractions of its height, that an under-display fingerprint
 * sensor's icon covers on the lock screen. Nothing worth reading is drawn inside it.
 */
data class FingerprintZone(val top: Float, val bottom: Float) {
    companion object {
        /**
         * Fallback when the phone doesn't say where its sensor is. On the Pixel this was
         * measured on, the icon spans about 69–78% of the height; padded a little for the touch halo
         * and for phones that put the sensor a little higher or lower.
         */
        val DEFAULT = FingerprintZone(0.67f, 0.80f)

        /**
         * From the sensor's centre and radius in the same pixels as [screenHeight]. Null when
         * the numbers don't look like a sensor in the lower half of a portrait screen.
         */
        fun fromSensor(centerY: Float, radius: Float, screenHeight: Float): FingerprintZone? {
            if (screenHeight <= 0f || radius <= 0f) return null
            val c = centerY / screenHeight
            val r = radius / screenHeight
            if (c !in 0.5f..0.97f || r > 0.08f) return null
            // The drawn icon and its press ring are larger than the sensor itself.
            val pad = r + 0.03f
            return FingerprintZone((c - pad).coerceAtLeast(0f), (c + pad).coerceAtMost(1f))
        }
    }
}

/** Where the lock screen's two blocks go, in pixels from the top, and how much the text shrank. */
data class LockPlacement(
    val summaryTop: Float,
    val weekTop: Float,
    /** False when the week row had to join the summary above the sensor. */
    val weekBelowZone: Boolean,
    val scale: Float,
)

/**
 * Lays the summary out around the fingerprint sensor: the day's lines sit just above it,
 * growing upward as lines wrap, and the week's dots sit just below it. Pure arithmetic so
 * the rules can be unit tested without drawing.
 */
object LockLayout {
    /** Below the clock, the date and weather line, an at-a-glance card and the chip under it. */
    const val TOP_LIMIT = 0.47f
    /** Above the bottom shortcuts. */
    const val BOTTOM_LIMIT = 0.90f
    const val MIN_SCALE = 0.7f

    /**
     * [summaryHeight] and [weekHeight] give each block's height in pixels at a text scale;
     * [gap] is the clear space kept between a block and the sensor band.
     */
    fun place(
        height: Float,
        zone: FingerprintZone,
        gap: Float,
        summaryHeight: (Float) -> Float,
        weekHeight: (Float) -> Float,
    ): LockPlacement {
        val zoneTop = zone.top * height
        val zoneBottom = zone.bottom * height
        val top = TOP_LIMIT * height
        val weekBelow = zoneBottom + gap + weekHeight(1f) <= BOTTOM_LIMIT * height

        fun at(scale: Float): LockPlacement {
            val summary = summaryHeight(scale)
            val above = summary + if (weekBelow) 0f else gap + weekHeight(scale)
            val summaryTop = (zoneTop - gap - above).coerceAtLeast(0f)
            val weekTop = if (weekBelow) zoneBottom + gap else summaryTop + summary + gap
            return LockPlacement(summaryTop, weekTop, weekBelow, scale)
        }

        var scale = 1f
        while (scale > MIN_SCALE + 0.001f) {
            val p = at(scale)
            if (p.summaryTop >= top) return p
            scale -= 0.05f
        }
        // Still too tall: let it reach up into the card area rather than down under the sensor.
        return at(MIN_SCALE)
    }
}
