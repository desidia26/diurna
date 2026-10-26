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
    private val gap = 1.2f * u
    private val zone = FingerprintZone.DEFAULT
    /** The date and [lines] lines of text, roughly as drawn. */
    private fun summary(lines: Int) = { scale: Float -> (6.2f * u + lines * 5.4f * u + (lines - 1) * 1.8f * u) * scale }
    private val week = { scale: Float -> 10.2f * u * scale }

    private fun place(lines: Int, z: FingerprintZone = zone, height: Float = h, style: LockStyle = LockStyle.AUTO) =
        LockLayout.place(height, z, gap, style, summary(lines), week)

    private fun LockPlacement.summaryBottom(lines: Int) = summaryTop + summary(lines)(summaryScale)

    @Test fun summaryAboveTheSensorAndWeekBelow() {
        val p = place(5)
        assertTrue(p.weekBelowZone)
        assertTrue("summary ends above the sensor", p.summaryBottom(5) <= zone.top * h)
        assertTrue("and close to it", p.summaryBottom(5) >= zone.top * h - 2 * gap)
        assertTrue("week starts below the sensor", p.weekTop >= zone.bottom * h)
        assertTrue("week ends above the shortcuts", p.weekTop + week(p.weekScale) <= LockLayout.SHORTCUTS_TOP * h)
    }

    @Test fun typicalDayIsFullSizeAndClearsANotification() {
        // Four rows, the food line wrapping: on Larry's Pixel a notification card ends at 48%.
        val p = place(5)
        assertEquals(1f, p.summaryScale, 0.001f)
        assertTrue("top at ${p.summaryTop / h}", p.summaryTop >= 0.49f * h)
    }

    @Test fun wrappedLinesGrowUpwardNotIntoTheSensor() {
        val four = place(4)
        val five = place(5)
        assertTrue(five.summaryTop < four.summaryTop)
        assertEquals(four.summaryBottom(4), five.summaryBottom(5), 0.01f)
    }

    @Test fun tooManyLinesShrinkRatherThanReachTheCards() {
        val p = place(7)
        assertTrue(p.summaryScale < 1f)
        assertTrue(p.summaryTop >= LockLayout.TOP_LIMIT * h)
        assertTrue(p.summaryBottom(7) <= zone.top * h)
    }

    @Test fun shortScreenShrinksTextToFit() {
        val height = 1920f // 16:9
        val p = place(6, height = height)
        assertTrue(p.summaryScale < 1f)
        assertTrue(p.summaryTop >= LockLayout.TOP_LIMIT * height)
        assertTrue(p.summaryBottom(6) <= zone.top * height)
    }

    @Test fun neverGoesUnderTheSensorEvenWhenItCantFit() {
        val p = place(14)
        assertEquals(LockLayout.MIN_SCALE, p.summaryScale, 0.001f)
        assertTrue(p.summaryBottom(14) <= zone.top * h)
    }

    @Test fun reportedSensorPositionIsUsed() {
        val reported = FingerprintZone.fromSensor(centerY = 0.80f * h, radius = 0.025f * h, screenHeight = h)!!
        val p = place(5, reported)
        assertTrue(p.summaryBottom(5) <= reported.top * h && p.summaryBottom(5) > zone.top * h)
        assertTrue(p.weekTop >= reported.bottom * h)
    }

    @Test fun lowSensorMovesTheWeekAboveIt() {
        val low = FingerprintZone(0.84f, 0.95f)
        val p = place(5, low)
        assertFalse(p.weekBelowZone)
        assertTrue("week above the sensor", p.weekTop + week(p.weekScale) <= low.top * h)
        assertTrue("lines above the week", p.summaryBottom(5) <= p.weekTop)
    }

    @Test fun raiseMovesOnlyTheSummaryUp() {
        val auto = place(5)
        val raised = place(5, style = LockStyle(raise = 0.05f))
        assertEquals(auto.summaryTop - 0.05f * h, raised.summaryTop, 0.5f)
        assertEquals(auto.weekTop, raised.weekTop, 0.01f)
        assertEquals(1f, raised.summaryScale, 0.001f)
    }

    @Test fun raiseIsClampedSoItNeverReachesTheSensorOrTheClock() {
        // Negative would mean lower than Auto, into the sensor band.
        val lowered = place(5, style = LockStyle(raise = -0.2f))
        assertTrue(lowered.summaryBottom(5) <= zone.top * h)
        assertEquals(place(5).summaryTop, lowered.summaryTop, 0.01f)
        val way = place(9, style = LockStyle(raise = 1f, textScale = 1.25f))
        assertTrue("top at ${way.summaryTop / h}", way.summaryTop >= LockLayout.CLOCK_LIMIT * h)
        assertEquals(place(9, style = LockStyle(raise = LockStyle.MAX_RAISE, textScale = 1.25f)), way)
    }

    @Test fun textSizeScalesBothBlocks() {
        val big = place(5, style = LockStyle(textScale = 1.2f))
        assertEquals(1.2f, big.summaryScale, 0.001f)
        assertEquals(1.2f, big.weekScale, 0.001f)
        assertTrue(big.summaryBottom(5) <= zone.top * h)
        assertTrue(big.summaryTop >= LockLayout.CLOCK_LIMIT * h)
        // A long day still shrinks from the chosen size rather than climbing further.
        val bigLong = place(7, style = LockStyle(textScale = 1.2f))
        assertTrue(bigLong.summaryScale < 1.2f)
        val small = place(5, style = LockStyle(textScale = 0.5f))
        assertEquals(LockStyle.MIN_TEXT, small.summaryScale, 0.001f)
    }

    @Test fun sensorZoneFromPlatformProps() {
        // A 1440 x 3120 panel with the sensor three quarters of the way down.
        val z = FingerprintZone.fromSensor(centerY = 2340f, radius = 90f, screenHeight = 3120f)!!
        assertTrue(z.top < 0.75f - 90f / 3120f && z.bottom > 0.75f + 90f / 3120f)
        assertNull("top half isn't a UDFPS", FingerprintZone.fromSensor(500f, 90f, 2400f))
        assertNull(FingerprintZone.fromSensor(2000f, 0f, 2400f))
    }
}
