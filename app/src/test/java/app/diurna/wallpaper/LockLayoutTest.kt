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
    private val zone = FingerprintZone.DEFAULT
    private fun summary(rows: Int) = { scale: Float -> (4 * u + rows * 7 * u) * scale }
    private val week = { scale: Float -> 10.5f * u * scale }

    private fun place(summary: (Float) -> Float, z: FingerprintZone = zone, height: Float = h) =
        LockLayout.place(height, z, 3 * u, summary, week)

    @Test fun summarySitsAboveTheSensorAndWeekBelow() {
        val p = place(summary(4))
        assertEquals(1f, p.scale)
        assertTrue(p.weekBelowZone)
        assertTrue("summary ends above the sensor", p.summaryTop + summary(4)(1f) <= zone.top * h)
        assertTrue("below the cards", p.summaryTop >= LockLayout.TOP_LIMIT * h)
        assertTrue("week starts below the sensor", p.weekTop >= zone.bottom * h)
        assertTrue("week ends above the shortcuts", p.weekTop + week(1f) <= LockLayout.BOTTOM_LIMIT * h)
    }

    @Test fun wrappedLinesGrowUpwardNotIntoTheSensor() {
        val short = place(summary(4))
        val wrapped = place(summary(5))
        assertTrue(wrapped.summaryTop < short.summaryTop)
        assertEquals(short.summaryTop + summary(4)(1f), wrapped.summaryTop + summary(5)(1f), 0.01f)
    }

    @Test fun shortScreenShrinksTextToFit() {
        // A 16:9 screen with a wrapped row.
        val height = 1920f
        val p = place(summary(5), height = height)
        assertTrue(p.scale < 1f)
        assertTrue(p.summaryTop >= LockLayout.TOP_LIMIT * height)
        assertTrue(p.summaryTop + summary(5)(p.scale) <= zone.top * height)
    }

    @Test fun neverGoesUnderTheSensorEvenWhenItCantFit() {
        val p = place(summary(12))
        assertEquals(LockLayout.MIN_SCALE, p.scale, 0.001f)
        assertTrue(p.summaryTop + summary(12)(p.scale) <= zone.top * h + 0.01f)
    }

    @Test fun lowSensorMovesTheWeekAboveIt() {
        val low = FingerprintZone(0.82f, 0.93f)
        val p = place(summary(4), low)
        assertFalse(p.weekBelowZone)
        assertTrue(p.weekTop + week(p.scale) <= low.top * h)
        assertTrue(p.weekTop >= p.summaryTop + summary(4)(p.scale))
    }

    @Test fun sensorZoneFromPlatformProps() {
        // A 1440 x 3120 panel with the sensor three quarters of the way down.
        val z = FingerprintZone.fromSensor(centerY = 2340f, radius = 90f, screenHeight = 3120f)!!
        assertTrue(z.top < 0.75f - 90f / 3120f && z.bottom > 0.75f + 90f / 3120f)
        assertNull("top half isn't a UDFPS", FingerprintZone.fromSensor(500f, 90f, 2400f))
        assertNull(FingerprintZone.fromSensor(2000f, 0f, 2400f))
    }
}
