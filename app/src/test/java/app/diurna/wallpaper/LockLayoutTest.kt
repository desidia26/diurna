package app.diurna.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockLayoutTest {
    // A 1080 x 2424 Pixel: 1 width unit is 10.8 px.
    private val h = 2424f
    private val u = 10.8f
    private val gap = 2.5f * u
    private val zone = FingerprintZone.DEFAULT
    /** Date, [rows] text lines and the week row, roughly as drawn. */
    private fun block(rows: Int) = { scale: Float -> (6 * u + rows * 5.5f * u + 11 * u) * scale }

    private fun place(block: (Float) -> Float, z: FingerprintZone = zone, height: Float = h) =
        LockLayout.place(height, z, gap, block)

    @Test fun summarySitsBetweenTheSensorAndTheShortcuts() {
        val p = place(block(4))
        assertTrue(p.belowSensor)
        assertTrue("starts below the sensor", p.top >= zone.bottom * h)
        assertTrue("ends above the bottom", p.top + block(4)(p.scale) <= LockLayout.BOTTOM_LIMIT * h)
    }

    @Test fun nothingInTheNotificationArea() {
        // Notifications stack down from under the clock to the sensor; the summary stays out of all of it.
        for (rows in 3..7) {
            val p = place(block(rows))
            assertTrue("$rows rows", p.belowSensor && p.top >= zone.bottom * h)
        }
    }

    @Test fun wrappedLinesShrinkTheTextToFit() {
        val short = place(block(3))
        val wrapped = place(block(6))
        assertTrue(wrapped.scale < short.scale)
        assertEquals(short.top, wrapped.top, 0.01f)
        assertTrue(wrapped.top + block(6)(wrapped.scale) <= LockLayout.BOTTOM_LIMIT * h)
    }

    @Test fun shortScreenStillFitsBelowTheSensor() {
        val height = 1920f // 16:9
        val p = place(block(5), height = height)
        assertTrue(p.belowSensor)
        assertTrue(p.top + block(5)(p.scale) <= LockLayout.BOTTOM_LIMIT * height)
    }

    @Test fun reportedSensorPositionIsUsed() {
        // A sensor reported higher than the fallback leaves more room, so less shrinking.
        val high = FingerprintZone.fromSensor(centerY = 0.62f * h, radius = 0.025f * h, screenHeight = h)!!
        val p = place(block(5), high)
        assertTrue(p.top >= high.bottom * h && p.top < zone.bottom * h)
        assertTrue(p.scale >= place(block(5)).scale)
    }

    @Test fun sensorTooLowFallsBackAboveItNeverUnderIt() {
        val low = FingerprintZone(0.84f, 0.94f)
        val p = place(block(5), low)
        assertFalse(p.belowSensor)
        assertTrue(p.top + block(5)(p.scale) <= low.top * h)
        assertTrue(p.top >= LockLayout.TOP_LIMIT * h)
    }

    @Test fun sensorZoneFromPlatformProps() {
        // A 1440 x 3120 panel with the sensor three quarters of the way down.
        val z = FingerprintZone.fromSensor(centerY = 2340f, radius = 90f, screenHeight = 3120f)!!
        assertTrue(z.top < 0.75f - 90f / 3120f && z.bottom > 0.75f + 90f / 3120f)
        assertNull("top half isn't a UDFPS", FingerprintZone.fromSensor(500f, 90f, 2400f))
        assertNull(FingerprintZone.fromSensor(2000f, 0f, 2400f))
    }
}
