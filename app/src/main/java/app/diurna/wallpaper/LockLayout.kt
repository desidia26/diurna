package app.diurna.wallpaper

/**
 * The band of the screen, as fractions of its height, that an under-display fingerprint
 * sensor's icon covers on the lock screen. Nothing is drawn inside it.
 */
data class FingerprintZone(val top: Float, val bottom: Float) {
    companion object {
        /**
         * Fallback when the phone doesn't say where its sensor is. On the Pixel this was
         * measured on, the icon spans about 69–78% of the screen height as seen (after the
         * wallpaper zoom); padded a little.
         */
        val DEFAULT = FingerprintZone(0.68f, 0.79f)

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

/**
 * The user's adjustments from Setup. [raise] lifts the summary by a fraction of the screen
 * height from where Auto puts it (just above the sensor, which is also as low as it can go);
 * [textScale] multiplies every size.
 */
data class LockStyle(val raise: Float = 0f, val textScale: Float = 1f) {
    val isAuto get() = this == AUTO

    fun clamped() = LockStyle(raise.coerceIn(0f, MAX_RAISE), textScale.coerceIn(MIN_TEXT, MAX_TEXT))

    companion object {
        val AUTO = LockStyle()
        const val MAX_RAISE = 0.15f
        const val MIN_TEXT = 0.8f
        const val MAX_TEXT = 1.25f
    }
}

/** Where the lock screen's two blocks go, in screen pixels from the top, and their text scales. */
data class LockPlacement(
    val summaryTop: Float,
    val summaryScale: Float,
    val weekTop: Float,
    val weekScale: Float,
    /** False when the week row had to join the summary above the sensor. */
    val weekBelowZone: Boolean,
)

/**
 * Splits the lock screen around the fingerprint sensor: the day's lines sit just above it,
 * growing upward as lines wrap, and the week's dots sit below it, above the bottom shortcuts.
 * Pure arithmetic so the rules can be unit tested without drawing.
 *
 * There's no API for how far down notifications reach. They stack from under the clock and
 * the lock screen folds extras into a shelf of icons, so in practice one or two cards end
 * around the middle of the screen; [TOP_LIMIT] is measured from that on a Pixel 9a.
 */
object LockLayout {
    /** Below the clock, the date and weather line, the at-a-glance card and a notification or two. */
    const val TOP_LIMIT = 0.47f
    /** The clock and date line: not even a raised summary goes above this. */
    const val CLOCK_LIMIT = 0.25f
    /** Where the bottom shortcuts start; the week's dots centre between the sensor and these. */
    const val SHORTCUTS_TOP = 0.91f
    /** Lowest the dots may reach, between the two shortcut buttons and above the navigation handle. */
    const val BOTTOM_LIMIT = 0.96f
    const val MIN_SCALE = 0.7f

    /**
     * [summaryHeight] and [weekHeight] give each block's height in pixels at a text scale;
     * [gap] is the clear space kept between a block and the sensor band.
     */
    fun place(
        height: Float,
        zone: FingerprintZone,
        gap: Float,
        style: LockStyle = LockStyle.AUTO,
        summaryHeight: (Float) -> Float,
        weekHeight: (Float) -> Float,
    ): LockPlacement {
        val st = style.clamped()
        val k = st.textScale
        val zoneTop = zone.top * height
        val zoneBottom = zone.bottom * height

        val week = weekHeight(k)
        val weekBelow = zoneBottom + gap + week <= BOTTOM_LIMIT * height
        val weekTop = if (weekBelow) {
            ((zoneBottom + SHORTCUTS_TOP * height) / 2 - week / 2).coerceIn(zoneBottom + gap, BOTTOM_LIMIT * height - week)
        } else 0f

        // Raising only ever moves up, so the block can't reach the sensor band.
        val bottom = zoneTop - gap - st.raise * height
        // A chosen raise or bigger text moves the ceiling up with it, so asking for either gets it
        // rather than being undone by the shrink, which is for days too long for the space.
        val grown = (summaryHeight(k) - summaryHeight(1f)).coerceAtLeast(0f)
        val limit = ((TOP_LIMIT - st.raise) * height - grown).coerceAtLeast(CLOCK_LIMIT * height)
        fun at(scale: Float): LockPlacement {
            val summary = summaryHeight(scale)
            return if (weekBelow) {
                LockPlacement(bottom - summary, scale, weekTop, k, true)
            } else {
                // No room under the sensor (it sits very low): the dots go under the lines, above it.
                val w = weekHeight(scale)
                LockPlacement(bottom - w - gap - summary, scale, bottom - w, scale, false)
            }
        }

        var scale = k
        while (scale > MIN_SCALE + 0.001f) {
            val p = at(scale)
            if (p.summaryTop >= limit) return p
            scale -= 0.05f
        }
        // Still too tall: let it reach up into the card area rather than down under the sensor.
        return at(minOf(MIN_SCALE, k))
    }
}
