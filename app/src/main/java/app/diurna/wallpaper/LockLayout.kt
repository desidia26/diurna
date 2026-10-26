package app.diurna.wallpaper

/**
 * The band of the screen, as fractions of its height, that an under-display fingerprint
 * sensor's icon covers on the lock screen. Nothing is drawn inside it.
 */
data class FingerprintZone(val top: Float, val bottom: Float) {
    companion object {
        /**
         * Fallback when the phone doesn't say where its sensor is. On the Pixel this was
         * measured on, the icon spans about 69–78% of the screen height; padded a little.
         */
        val DEFAULT = FingerprintZone(0.675f, 0.79f)

        /**
         * From the sensor's centre and radius in the same pixels as [screenHeight]. Null when
         * the numbers don't look like a sensor in the lower half of a portrait screen.
         */
        fun fromSensor(centerY: Float, radius: Float, screenHeight: Float): FingerprintZone? {
            if (screenHeight <= 0f || radius <= 0f) return null
            val c = centerY / screenHeight
            val r = radius / screenHeight
            if (c !in 0.5f..0.97f || r > 0.08f) return null
            // The drawn icon is a little larger than the sensor itself.
            val pad = r * 1.4f + 0.005f
            return FingerprintZone((c - pad).coerceAtLeast(0f), (c + pad).coerceAtMost(1f))
        }
    }
}

/** Where the lock screen's block goes, in screen pixels from the top, and how much its text shrank. */
data class LockPlacement(
    val top: Float,
    /** False only when the sensor sits too low to leave room under it. */
    val belowSensor: Boolean,
    val scale: Float,
)

/**
 * Puts the summary between the fingerprint sensor and the bottom shortcuts. Everything above
 * the sensor belongs to notifications: SystemUI stacks them down from under the clock and only
 * stops at the sensor, and a wallpaper can't know how many there are. Pure arithmetic so the
 * rules can be unit tested without drawing.
 */
object LockLayout {
    /** Fallback only: below the clock, the date line and an at-a-glance card. */
    const val TOP_LIMIT = 0.47f
    /** Bottom of the space between the two shortcut buttons, above the navigation handle. */
    const val BOTTOM_LIMIT = 0.96f
    const val MIN_SCALE = 0.6f

    /** [blockHeight] gives the block's height in pixels at a text scale; [gap] is the clear space kept from the sensor band. */
    fun place(height: Float, zone: FingerprintZone, gap: Float, blockHeight: (Float) -> Float): LockPlacement {
        val belowTop = zone.bottom * height + gap
        val belowRoom = BOTTOM_LIMIT * height - belowTop
        val aboveBottom = zone.top * height - gap
        val aboveRoom = aboveBottom - TOP_LIMIT * height

        fun fit(room: Float): Float? {
            var scale = 1f
            while (scale >= MIN_SCALE - 0.001f) {
                if (blockHeight(scale) <= room) return scale
                scale -= 0.05f
            }
            return null
        }

        fit(belowRoom)?.let { return LockPlacement(belowTop, true, it) }
        // A sensor this low leaves no room under it. Above it notifications may cover the
        // summary, which still beats the sensor covering it every time.
        fit(aboveRoom)?.let { return LockPlacement(aboveBottom - blockHeight(it), false, it) }
        return if (belowRoom >= aboveRoom) {
            LockPlacement(belowTop, true, MIN_SCALE)
        } else {
            LockPlacement((aboveBottom - blockHeight(MIN_SCALE)).coerceAtLeast(0f), false, MIN_SCALE)
        }
    }
}
