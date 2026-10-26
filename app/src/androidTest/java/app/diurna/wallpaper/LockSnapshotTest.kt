package app.diurna.wallpaper

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.data.TestDb
import app.diurna.domain.AlarmRules
import app.diurna.domain.AlarmRules.Morning
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
        t.db.foodDao().setEstimate(bowl.id, bowl.text, 700, 900, 45, 80, 28, "", "fake", 1L)
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
    private fun android.graphics.Bitmap.rowIsBlank(y: Int) = (0 until width).all { getPixel(it, y) == getPixel(0, y) }

    @Test fun nothingDrawnUnderTheFingerprintSensor() = runTest {
        for ((w, h) in listOf(1080 to 2424, 1080 to 2400, 720 to 1280, 1440 to 3120)) {
            val zone = FingerprintZone.DEFAULT
            val bmp = draw(busyDay(), w, h, zone)
            val drawnIn = ((zone.top * h).toInt()..(zone.bottom * h).toInt()).filterNot { bmp.rowIsBlank(it) }
            assertTrue("${w}x$h: rows drawn under the sensor: ${drawnIn.take(5)}", drawnIn.isEmpty())
        }
    }

    @Test fun longLinesWrapInsteadOfRunningOffTheEdge() = runTest {
        val (w, h) = 1080 to 2424
        val bmp = draw(busyDay(), w, h)
        // The right margin matches the left one, so its last columns stay empty.
        val margin = (w * 0.05f).toInt()
        val clipped = (0 until h).filter { y -> (w - margin until w).any { bmp.getPixel(it, y) != bmp.getPixel(0, y) } }
        assertTrue("drawn into the right margin at rows ${clipped.take(5)}", clipped.isEmpty())
    }

    @Test fun stepsLineAndGoal() = runTest {
        assertNull(buildSnapshot(t.db, t.days, t.now).stepsLine)
        val under = buildSnapshot(t.db, t.days, t.now, steps = 6_240, stepGoal = 10_000)
        assertEquals("6,240 / 10,000 steps", under.stepsLine)
        assertFalse(under.stepsReached)
        assertTrue(buildSnapshot(t.db, t.days, t.now, steps = 10_000, stepGoal = 10_000).stepsReached)
    }
}
