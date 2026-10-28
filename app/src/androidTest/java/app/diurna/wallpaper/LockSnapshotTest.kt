package app.diurna.wallpaper

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.data.TestDb
import app.diurna.domain.AlarmRules
import app.diurna.domain.AlarmRules.Morning
import app.diurna.domain.Cardio
import app.diurna.domain.CardioPiece
import app.diurna.domain.DayText
import app.diurna.domain.Targets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockSnapshotTest {
    private val t = TestDb()
    private val mon = t.mon

    @After fun tearDown() = t.close()

    private suspend fun snap() = buildSnapshot(t.db, t.days, t.now)

    @Test fun unscannedMorningFollowsTheClock() = runTest {
        t.now = mon.atTime(5, 0)
        assertEquals("Alarm at 06:30", snap().morningLine)
        t.now = mon.atTime(6, 45)
        assertEquals("Alarm 06:30 · not scanned yet", snap().morningLine)
        t.now = mon.atTime(7, 30)
        assertEquals("Missed the 06:30 alarm", snap().morningLine)
        assertEquals(LockSnapshot.Tone.Warn, snap().morningTone)
    }

    @Test fun emptyDay() = runTest {
        val s = snap()
        assertEquals("Missed the 06:30 alarm", s.morningLine)
        assertEquals("Rest day", s.workoutLine)
        assertEquals("No food logged yet", s.foodLine)
        assertNull("before 10pm, tomorrow isn't shown", s.tomorrowLine)
        assertEquals(mon, s.week.first().first)
    }

    @Test fun morningLines() = runTest {
        t.days.recordMorning(mon, "06:35", "scan")
        assertEquals("Up at 06:35 · on time", snap().morningLine)
        assertEquals(LockSnapshot.Tone.Good, snap().morningTone)
        t.days.recordMorning(mon, "06:52", "scan")
        assertEquals("Up at 06:52 · 22 min late", snap().morningLine)
        assertEquals(LockSnapshot.Tone.Warn, snap().morningTone)
    }

    @Test fun workoutPlannedThenInProgressThenDone() = runTest {
        val push = t.template("Push A", "Bench press", "Dips")
        t.routine.setTemplate(mon.dayOfWeek.value, push)
        assertEquals("Push A planned", snap().workoutLine)

        t.days.startWorkout(mon, push)
        t.fill(mon, "Bench press", 185.0, 8, 8, 8)
        assertEquals("Push A · 1 of 2 exercises", snap().workoutLine)

        t.fill(mon, "Dips", 0.0, 12)
        assertEquals("Push A · 2 of 2 exercises", snap().workoutLine)
        assertTrue(snap().workoutDone)
    }

    /** The in-memory test database isn't seeded, so cardio exercises are added with their style. */
    private suspend fun cardio(name: String, style: Cardio.Style) = t.library.addExercise(name, style)

    private suspend fun logOnly(piece: CardioPiece) {
        val set = t.days.observeWorkout(mon).first()!!.exercises.single().sets.single()
        t.days.updatePiece(set.id, piece)
    }

    @Test fun aRowOnItsOwnReadsAsThePiece() = runTest {
        cardio("Rowing machine", Cardio.Style.ROW)
        t.days.startBlankWorkout(mon)
        t.days.addExercise(mon, "Rowing machine")
        assertEquals("Rowing machine · not logged yet", snap().workoutLine)
        assertFalse(snap().workoutDone)
        logOnly(CardioPiece(3260, 907.0, 130.0, 104.0, 158, 120, null))
        assertEquals("Rowing machine · 3,260 m · 2:10/500m", snap().workoutLine)
        assertTrue(snap().workoutDone)
    }

    @Test fun aRunReadsInItsUnits() = runTest {
        cardio("Run", Cardio.Style.RUN_KM)
        t.days.startBlankWorkout(mon)
        t.days.addExercise(mon, "Run")
        logOnly(CardioPiece(distanceM = 5000, durationS = 1560.0))
        assertEquals("Run · 5.0 km · 5:12/km", snap().workoutLine)
    }

    @Test fun anEllipticalReadsAsTimeAndCalories() = runTest {
        cardio("Elliptical", Cardio.Style.TIME)
        t.days.startBlankWorkout(mon)
        t.days.addExercise(mon, "Elliptical")
        logOnly(CardioPiece(durationS = 1800.0, kcal = 250))
        assertEquals("Elliptical · 30 min · 250 kcal", snap().workoutLine)
        assertTrue(snap().workoutDone)
    }

    @Test fun rowAfterLiftingAddsTheMetres() = runTest {
        cardio("Rowing machine", Cardio.Style.ROW)
        val push = t.template("Push A", "Bench press", "Rowing machine")
        t.days.startWorkout(mon, push)
        t.fill(mon, "Bench press", 185.0, 8, 8, 8)
        assertEquals("Push A · 1 of 2 exercises", snap().workoutLine)
        val row = t.days.observeWorkout(mon).first()!!.exercises.first { it.exercise.name == "Rowing machine" }
        t.days.updatePiece(row.sets.single().id, CardioPiece(distanceM = 2000, durationS = 480.0))
        assertEquals("Push A · 2 of 2 exercises · 2,000 m cardio", snap().workoutLine)
    }

    /** Someone who only lifts gets exactly the line from before cardio existed. */
    @Test fun liftingOnlyLinesAreUnchanged() = runTest {
        val push = t.template("Push A", "Bench press", "Squat")
        t.days.startWorkout(mon, push)
        assertEquals(DayText.workout("Push A", 0, 2), snap().workoutLine)
        t.fill(mon, "Bench press", 185.0, 8, 8, 8)
        assertEquals(DayText.workout("Push A", 1, 2), snap().workoutLine)
        assertEquals("Push A · 1 of 2 exercises", snap().workoutLine)
    }

    @Test fun foodLine() = runTest {
        t.now = mon.atTime(8, 15); t.food.add(mon, "eggs")
        assertEquals("1 food entry · 08:15", snap().foodLine)
        t.now = mon.atTime(12, 40); t.food.add(mon, "burrito")
        assertEquals("2 food entries · last 12:40", snap().foodLine)
    }

    @Test fun nutritionReplacesFoodLineOnceEstimated() = runTest {
        t.food.add(mon, "burrito bowl")
        t.food.add(mon, "protein shake")
        assertEquals("2 food entries · last 12:00", snap().foodLine)
        val bowl = t.food.observe(mon).first().first { it.text == "burrito bowl" }
        t.db.foodDao().setEstimate(bowl.id, bowl.text, null, 700, 900, 45, 80, 28, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "", "fake", 1L)
        assertEquals("~800 kcal · P 45 · C 80 · F 28 · 1 estimating", buildSnapshot(t.db, t.days, t.now).foodLine)
        assertEquals(
            "800 / 2,260 kcal · P 45/180 · C 80/243 · F 28/63 · 1 estimating",
            buildSnapshot(t.db, t.days, t.now, Targets(2260, 180, 243, 63)).foodLine,
        )
    }

    @Test fun tomorrowShownAfterTenPm() = runTest {
        t.now = mon.atTime(22, 5)
        assertEquals("Tomorrow · 06:30 · locked", snap().tomorrowLine)
    }

    @Test fun tomorrowOffShownAfterTenPm() = runTest {
        t.days.setAlarmOverride(mon.plusDays(1), AlarmRules.OFF)
        t.now = mon.atTime(22, 5)
        assertEquals("Tomorrow · no alarm", snap().tomorrowLine)
    }

    @Test fun weekDotsReflectMornings() = runTest {
        // Sunday's view of the week: Mon on time, Tue late, Wed missed, Thu off.
        t.days.recordMorning(mon, "06:31", "scan")
        t.days.recordMorning(mon.plusDays(1), "07:10", "scan")
        t.days.setAlarmOverride(mon.plusDays(3), AlarmRules.OFF)
        t.now = mon.plusDays(6).atTime(12, 0)
        val week = snap().week.map { it.second }
        assertEquals(listOf(Morning.ON_TIME, Morning.LATE, Morning.MISSED, Morning.OFF), week.take(4))
        assertEquals("Sunday noon, unscanned", Morning.MISSED, week[6])
    }

    @Test fun drawsAFullScreenBitmap() = runTest {
        val bmp = draw(snap(), 1080, 2424)
        assertEquals(1080, bmp.width)
        assertEquals(2424, bmp.height)
    }

    /** The day from Larry's screenshot, with the long nutrition line that used to run off the edge. */
    private fun busyDay() = LockSnapshot(
        date = mon.plusDays(6),
        morningLine = "Missed the 06:30 alarm", morningTone = LockSnapshot.Tone.Warn,
        workoutLine = "Workout · 1 of 1 exercises", workoutDone = true,
        foodLine = "3,170 / 3,070 kcal · P 84/141 · C 445/386 · F 120/85 · 2 estimating",
        stepsLine = "525 / 10,000 steps", stepsReached = false,
        week = (0L..6L).map { mon.plusDays(it) to Morning.MISSED },
        tomorrowLine = "Tomorrow · 06:30 · locked",
    )

    /** The background is a vertical gradient, so a row with anything drawn on it isn't one colour. */
    private fun android.graphics.Bitmap.rowIsBlank(y: Int): Boolean {
        val row = IntArray(width).also { getPixels(it, 0, width, 0, y, width, 1) }
        return row.all { it == row[0] }
    }

    /** The bitmap row that the system's centred [zoom] shows at screen fraction [f]. */
    private fun bitmapRow(f: Float, h: Int, zoom: Float) = ((f * h - h / 2f) / zoom + h / 2f).toInt()

    private val sizes = listOf(1080 to 2424, 1080 to 2400, 720 to 1280, 1440 to 3120)

    private val styles = listOf(LockStyle.AUTO, LockStyle(raise = 0.06f), LockStyle(raise = LockStyle.MAX_RAISE, textScale = LockStyle.MAX_TEXT))

    @Test fun splitAroundTheSensorWithItsBandClear() {
        for ((w, h) in sizes.filter { (w, h) -> h >= 2 * w }) for (zoom in listOf(1f, 1.1f)) for (style in styles) {
            val zone = FingerprintZone.DEFAULT
            val bmp = draw(busyDay(), w, h, zone, zoom, style)
            val label = "${w}x$h zoom $zoom $style"
            val band = (bitmapRow(zone.top, h, zoom)..bitmapRow(zone.bottom, h, zoom)).filterNot { bmp.rowIsBlank(it) }
            assertTrue("$label: rows drawn under the sensor: ${band.take(5)}", band.isEmpty())
            assertTrue("$label: nothing above the sensor", (0 until bitmapRow(zone.top, h, zoom)).any { !bmp.rowIsBlank(it) })
            assertTrue("$label: nothing below the sensor", (bitmapRow(zone.bottom, h, zoom) until h).any { !bmp.rowIsBlank(it) })
            // Never up into the clock, however it's set.
            val clock = (0..bitmapRow(LockLayout.CLOCK_LIMIT, h, zoom)).filterNot { bmp.rowIsBlank(it) }
            assertTrue("$label: rows drawn by the clock: ${clock.take(5)}", clock.isEmpty())
            bmp.recycle() // a dozen full-screen bitmaps otherwise outgrow the test process
        }
    }

    @Test fun autoLeavesTheNotificationAreaClear() {
        // A busy day still stays under where a notification or two end, shrinking if it must.
        for ((w, h) in sizes.filter { (w, h) -> h >= 2 * w }) for (zoom in listOf(1f, 1.1f)) {
            val bmp = draw(busyDay(), w, h, FingerprintZone.DEFAULT, zoom)
            val drawn = (0..bitmapRow(LockLayout.TOP_LIMIT, h, zoom)).filterNot { bmp.rowIsBlank(it) }
            assertTrue("${w}x$h zoom $zoom: rows drawn in the notification area: ${drawn.take(5)}", drawn.isEmpty())
            bmp.recycle()
        }
    }

    @Test fun raisingMovesTheLinesUp() {
        val (w, h) = 1080 to 2424
        fun firstDrawn(style: LockStyle) = draw(busyDay(), w, h, zoom = 1.1f, style = style).let { b -> (0 until h).first { !b.rowIsBlank(it) }.also { b.recycle() } }
        assertTrue(firstDrawn(LockStyle(raise = 0.06f)) < firstDrawn(LockStyle.AUTO) - 0.04f * h)
    }

    @Test fun shortScreenNeverDrawsUnderTheSensorEither() {
        // 16:9 leaves too little room below a sensor at the fallback height, so the summary goes above it.
        val (w, h) = 720 to 1280
        val zone = FingerprintZone.DEFAULT
        for (zoom in listOf(1f, 1.1f)) {
            val bmp = draw(busyDay(), w, h, zone, zoom)
            val drawn = (bitmapRow(zone.top, h, zoom)..bitmapRow(zone.bottom, h, zoom)).filterNot { bmp.rowIsBlank(it) }
            assertTrue("zoom $zoom: rows drawn under the sensor: ${drawn.take(5)}", drawn.isEmpty())
        }
    }

    @Test fun reportedSensorPositionKeepsItsBandClear() {
        val (w, h) = 1080 to 2424
        val zone = FingerprintZone.fromSensor(centerY = 0.80f * h, radius = 0.02f * h, screenHeight = h.toFloat())!!
        val bmp = draw(busyDay(), w, h, zone, 1.1f)
        val drawn = (bitmapRow(zone.top, h, 1.1f)..bitmapRow(zone.bottom, h, 1.1f)).filterNot { bmp.rowIsBlank(it) }
        assertTrue("rows drawn under the sensor: ${drawn.take(5)}", drawn.isEmpty())
    }

    @Test fun longLinesWrapAndEverythingIsCentred() {
        for ((w, h) in sizes) {
            val bmp = draw(busyDay(), w, h, zoom = 1.1f)
            val drawn = BooleanArray(w)
            val row = IntArray(w)
            for (y in 0 until h step 2) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) if (row[x] != row[0]) drawn[x] = true
            }
            val drawnCols = (0 until w).filter { drawn[it] }
            val left = drawnCols.first()
            val right = w - 1 - drawnCols.last()
            // Clear of the corner shortcut buttons, and as much space on each side.
            // The lines are the widest thing and sit well above the corner shortcut buttons.
            assertTrue("${w}x$h: drawn ${left}px from the left edge", left > w * 0.07f)
            assertTrue("${w}x$h: drawn ${right}px from the right edge", right > w * 0.07f)
            assertTrue("${w}x$h: off centre by ${left - right}px", kotlin.math.abs(left - right) < w * 0.03f)
            bmp.recycle()
        }
    }

    @Test fun stepsLineAndGoal() = runTest {
        assertNull(buildSnapshot(t.db, t.days, t.now).stepsLine)
        val under = buildSnapshot(t.db, t.days, t.now, steps = 6_240, stepGoal = 10_000)
        assertEquals("6,240 / 10,000 steps", under.stepsLine)
        assertFalse(under.stepsReached)
        assertTrue(buildSnapshot(t.db, t.days, t.now, steps = 10_000, stepGoal = 10_000).stepsReached)
    }
}
