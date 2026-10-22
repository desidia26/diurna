package app.diurna.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.diurna.domain.AlarmRules
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DayRepositoryTest {
    private val t = TestDb()
    private val mon = t.mon
    private val tue = mon.plusDays(1)
    private val days = t.days

    @After fun tearDown() = t.close()

    private suspend fun workout(date: java.time.LocalDate) = days.observeWorkout(date).first()!!
    private fun WorkoutWithExercises.ex(name: String) = exercises.first { it.exercise.name.equals(name, ignoreCase = true) }

    // ---- Check-in ----

    @Test fun ratingsSaveClearAndStayEditableOnFinalizedDays() = runTest {
        val changed = mutableListOf<java.time.LocalDate>()
        days.onRatingChanged = { changed += it }
        days.setMood(mon, 4)
        assertFalse("energy still missing", days.isRated(mon))
        days.setEnergy(mon, 9)
        assertTrue(days.isRated(mon))
        assertEquals("clamped to the scale", 5, days.observeDay(mon).first()!!.energy)

        t.db.dayDao().finalize(mon.toString(), "06:00", null, null, null, null, null, null)
        days.setMood(mon, null)
        val d = days.observeDay(mon).first()!!
        assertNull(d.mood)
        assertTrue("finalizing doesn't touch ratings", d.finalized && d.energy == 5)
        assertEquals(listOf(mon, mon, mon), changed)
    }

    // ---- Workouts ----

    @Test fun lastTimeUsesMostRecentFilledSession() = runTest {
        val push = t.template("Push A", "Bench press", "Dips")
        val lastWeek = mon.minusDays(7)
        days.startWorkout(lastWeek, push)
        t.fill(lastWeek, "Bench press", 185.0, 8, 8, 6)

        days.startWorkout(mon, push)
        val bench = workout(mon).ex("Bench press")
        val last = days.lastTime(bench.exercise.id, mon)!!
        assertEquals(lastWeek, last.date)
        assertEquals(listOf(8, 8, 6), last.sets.map { it.reps })
        assertEquals("blank rows match last time's set count", 3, bench.sets.size)
        assertNull("never filled in, so no last time", days.lastTime(workout(mon).ex("Dips").exercise.id, mon))
    }

    @Test fun lastTimeSkipsSessionsWithNothingFilledIn() = runTest {
        val push = t.template("Push A", "Bench press")
        days.startWorkout(mon.minusDays(14), push)
        t.fill(mon.minusDays(14), "Bench press", 175.0, 8, 8, 8)
        days.startWorkout(mon.minusDays(7), push) // started, never filled

        days.startWorkout(mon, push)
        assertEquals(mon.minusDays(14), days.lastTime(workout(mon).ex("Bench press").exercise.id, mon)!!.date)
    }

    @Test fun removeExerciseTakesItsSetsAndLeavesTheRest() = runTest {
        days.startWorkout(mon, t.template("Push A", "Bench press", "Dips"))
        t.fill(mon, "Bench press", 185.0, 8, 8, 6)
        days.removeExercise(workout(mon).ex("Bench press").logged.id)
        val w = workout(mon)
        assertEquals(listOf("Dips"), w.exercises.map { it.exercise.name })
        assertEquals("no orphaned sets", 3, t.db.workoutDao().sets(w.ex("Dips").logged.id).size)
        assertTrue(t.db.query("SELECT COUNT(*) FROM exercise_set", null).use { it.moveToFirst(); it.getInt(0) } == 3)
    }

    @Test fun moveExerciseSwapsNeighborsAndStopsAtTheEnds() = runTest {
        days.startWorkout(mon, t.template("Push A", "Bench press", "Dips", "Lateral raise"))
        fun order() = runBlocking { workout(mon).exercises.sortedBy { it.logged.position }.map { it.exercise.name } }
        val dips = workout(mon).ex("Dips").logged.id
        days.moveExercise(dips, up = true)
        assertEquals(listOf("Dips", "Bench press", "Lateral raise"), order())
        days.moveExercise(dips, up = true) // already first
        assertEquals(listOf("Dips", "Bench press", "Lateral raise"), order())
        val raise = workout(mon).ex("Lateral raise").logged.id
        days.moveExercise(raise, up = false) // already last
        days.moveExercise(dips, up = false)
        assertEquals(listOf("Bench press", "Dips", "Lateral raise"), order())
    }

    @Test fun lastTimeStillWorksAfterReordering() = runTest {
        val push = t.template("Push A", "Bench press", "Dips")
        days.startWorkout(mon.minusDays(7), push)
        t.fill(mon.minusDays(7), "Dips", 0.0, 12, 10)
        days.moveExercise(workout(mon.minusDays(7)).ex("Dips").logged.id, up = true)
        days.startWorkout(mon, push)
        assertEquals(listOf(12, 10), days.lastTime(workout(mon).ex("Dips").exercise.id, mon)!!.sets.map { it.reps })
    }

    @Test fun neverDoneExerciseGetsThreeBlankSets() = runTest {
        days.startWorkout(mon, t.template("Legs", "Squat"))
        val squat = workout(mon).ex("Squat")
        assertEquals(3, squat.sets.size)
        assertTrue(squat.sets.all { it.weight == null && it.reps == null })
    }

    @Test fun addSetCopiesTheSetAbove() = runTest {
        days.startWorkout(mon, t.template("Push A", "Bench press"))
        t.fill(mon, "Bench press", 185.0, 8, 8, 6)
        days.addSet(workout(mon).ex("Bench press").logged.id)
        val sets = workout(mon).ex("Bench press").sets.sortedBy { it.position }
        assertEquals(4, sets.size)
        assertEquals(185.0, sets.last().weight)
        assertEquals(6, sets.last().reps)
    }

    @Test fun sameAsLastReplacesSetsWithLastTimesNumbers() = runTest {
        val push = t.template("Push A", "Bench press")
        days.startWorkout(mon.minusDays(7), push)
        t.fill(mon.minusDays(7), "Bench press", 185.0, 8, 8, 6)
        days.startWorkout(mon, push)
        val bench = workout(mon).ex("Bench press")
        days.useLast(bench.logged.id, bench.exercise.id, mon)
        val sets = workout(mon).ex("Bench press").sets.sortedBy { it.position }
        assertEquals(listOf(8, 8, 6), sets.map { it.reps })
        assertTrue(sets.all { it.weight == 185.0 })
    }

    @Test fun deletingLastSetRemovesTheExercise() = runTest {
        days.startWorkout(mon, t.template("Push A", "Bench press", "Dips"))
        val dips = workout(mon).ex("Dips")
        dips.sets.forEach { days.deleteSet(it.id, dips.logged.id) }
        assertEquals(listOf("Bench press"), workout(mon).exercises.map { it.exercise.name })
    }

    @Test fun removingWorkoutCascadesToSets() = runTest {
        days.startWorkout(mon, t.template("Push A", "Bench press"))
        days.removeWorkout(mon)
        assertNull(days.observeWorkout(mon).first())
        assertEquals(0, t.db.query("SELECT * FROM exercise_set", null).use { it.count })
    }

    @Test fun blankWorkoutTakesExercisesAsYouGo() = runTest {
        days.startBlankWorkout(mon)
        assertEquals("Workout", workout(mon).workout.name)
        assertTrue(workout(mon).exercises.isEmpty())
        days.addExercise(mon, "  Farmer carry ")
        assertEquals(listOf("Farmer carry"), workout(mon).exercises.map { it.exercise.name })
    }

    @Test fun exerciseNamesAreCaseInsensitive() = runTest {
        val push = t.template("Push A", "Bench press")
        days.startWorkout(mon.minusDays(7), push)
        t.fill(mon.minusDays(7), "Bench press", 185.0, 8, 8, 8)

        days.startBlankWorkout(mon)
        days.addExercise(mon, "bench PRESS")
        val added = workout(mon).ex("bench press")
        assertEquals("same catalog row as the template's", "Bench press", added.exercise.name)
        assertEquals(mon.minusDays(7), days.lastTime(added.exercise.id, mon)?.date)
    }

    // ---- Alarm and morning ----

    @Test fun overrideRefusedOnceLocked() = runTest {
        assertTrue(days.setAlarmOverride(tue, AlarmRules.OFF))
        t.now = mon.atTime(22, 1)
        assertFalse(days.setAlarmOverride(tue, null))
        assertEquals(AlarmRules.OFF, days.effectiveAlarm(tue))
    }

    @Test fun overrideMatchingRoutineIsStoredAsNoOverride() = runTest {
        days.setAlarmOverride(tue, "05:00")
        days.setAlarmOverride(tue, AppDatabase.DEFAULT_ALARM)
        assertNull(t.db.dayDao().get(tue.toString())?.alarmOverride)
    }

    @Test fun routineEditAfterLockKeepsArmedTime() = runTest {
        days.markScheduled(tue, "06:30")
        t.now = mon.atTime(22, 30)
        t.routine.setAlarm(tue.dayOfWeek.value, "08:00")
        assertEquals("06:30", days.effectiveAlarm(tue))
        assertEquals("next week's Tuesday follows the new routine", "08:00", days.effectiveAlarm(tue.plusDays(7)))
    }

    @Test fun routineEditAfterLockFreezesEvenIfNothingWasArmed() = runTest {
        // Porch code not confirmed yet, so the scheduler never armed Tuesday.
        t.now = mon.atTime(23, 0)
        t.routine.setAlarm(tue.dayOfWeek.value, "09:00")
        assertEquals(AppDatabase.DEFAULT_ALARM, days.effectiveAlarm(tue))
    }

    @Test fun markScheduledOverwritesOnlyWhileUnlocked() = runTest {
        days.markScheduled(tue, "06:30")
        days.markScheduled(tue, "07:00")
        assertEquals("07:00", t.db.dayDao().get(tue.toString())?.scheduledAlarm)
        t.now = mon.atTime(22, 0)
        days.markScheduled(tue, "08:00")
        assertEquals("07:00", t.db.dayDao().get(tue.toString())?.scheduledAlarm)
    }

    @Test fun scannedDayTellsSchedulerToSkipIt() = runTest {
        assertFalse(days.alarmInputs(mon).scanned)
        days.recordMorning(mon, "06:02", "scan")
        assertTrue(days.alarmInputs(mon).scanned)
        days.clearMorning(mon)
        assertFalse(days.alarmInputs(mon).scanned)
    }

    @Test fun alarmChangesNotifyTheScheduler() = runTest {
        var calls = 0
        days.onAlarmInputsChanged = { calls++ }
        days.setAlarmOverride(tue, "05:00")
        days.recordMorning(mon, "06:00", "scan")
        t.routine.setAlarm(3, "07:00")
        assertEquals(3, calls)
    }

    // ---- Food and week ----

    @Test fun foodTimeIsNowForTodayAndNoonWhenBackfilling() = runTest {
        t.now = mon.atTime(13, 45)
        t.food.add(mon, "  burrito bowl ")
        t.food.add(mon.minusDays(2), "pizza")
        assertEquals("13:45", t.food.observe(mon).first().single().time)
        assertEquals("burrito bowl", t.food.observe(mon).first().single().text)
        assertEquals("12:00", t.food.observe(mon.minusDays(2)).first().single().time)
    }

    @Test fun weekRowsCoverSevenDaysWithCalories() = runTest {
        t.food.add(mon, "eggs")
        t.food.add(mon, "coffee")
        t.food.add(mon, "still estimating")
        val (eggs, coffee) = t.food.observe(mon).first().filter { it.text != "still estimating" }
        val dao = t.db.foodDao()
        dao.setEstimate(eggs.id, eggs.text, 140, 180, 12, 1, 10, "", "test", 1L) // midpoint 160
        dao.setEstimate(coffee.id, coffee.text, 5, 10, 0, 1, 0, "", "test", 1L) // midpoint 7
        days.startWorkout(mon.plusDays(2), t.template("Pull A", "Row"))
        val week = days.observeWeek(mon).first()
        assertEquals(7, week.size)
        assertEquals("pending entries don't count", 167, week[0].kcal)
        assertNull("no food", week[1].kcal)
        assertEquals("Pull A", week[2].workoutName)
        assertNull(week[1].workoutName)
    }
}
