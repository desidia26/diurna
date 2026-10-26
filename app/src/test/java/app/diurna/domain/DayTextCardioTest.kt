package app.diurna.domain

import app.diurna.domain.DayText.ExerciseLine
import org.junit.Assert.assertEquals
import org.junit.Test

class DayTextCardioTest {
    private val row = Cardio.Style.ROW

    @Test fun aLoggedRowReadsAsThePiece() {
        val line = ExerciseLine("Rowing machine", cardio = true, done = true, distanceM = 3260, durationS = 907.0, splitS = 130.0, kcal = 120, style = row)
        assertEquals("Row · 3,260 m · 2:10/500m", DayText.workout("Row", listOf(line)))
        assertEquals("Rowing machine · 3,260 m · 2:10/500m", DayText.workout(DayText.BLANK_WORKOUT, listOf(line)))
    }

    @Test fun aRunReadsInItsOwnUnits() {
        val km = ExerciseLine("Run", cardio = true, done = true, distanceM = 5000, durationS = 1560.0, style = Cardio.Style.RUN_KM)
        assertEquals("Run · 5.0 km · 5:12/km", DayText.workout(DayText.BLANK_WORKOUT, listOf(km)))
        val mi = ExerciseLine("Treadmill", cardio = true, done = true, distanceM = 5000, durationS = 1560.0, style = Cardio.Style.RUN_MI)
        assertEquals("Treadmill · 3.1 mi · 8:22/mi", DayText.workout(DayText.BLANK_WORKOUT, listOf(mi)))
    }

    @Test fun aRideReadsAsSpeed() {
        val bike = ExerciseLine("Stationary bike", cardio = true, done = true, distanceM = 16093, durationS = 2220.0, style = Cardio.Style.RIDE_MI)
        assertEquals("Stationary bike · 10.0 mi · 16.2 mph", DayText.workout(DayText.BLANK_WORKOUT, listOf(bike)))
    }

    @Test fun timeOnlyMachinesReadAsTimeAndCalories() {
        val e = ExerciseLine("Elliptical", cardio = true, done = true, durationS = 1800.0, kcal = 250, style = Cardio.Style.TIME)
        assertEquals("Elliptical · 30 min · 250 kcal", DayText.workout(DayText.BLANK_WORKOUT, listOf(e)))
        assertEquals("Elliptical · 30 min", DayText.workout(DayText.BLANK_WORKOUT, listOf(e.copy(kcal = null))))
    }

    @Test fun noPaceShownIsWorkedOut() {
        val line = ExerciseLine("Rowing machine", cardio = true, done = true, distanceM = 2000, durationS = 480.0, style = row)
        assertEquals("Row · 2,000 m · 2:00/500m", DayText.workout("Row", listOf(line)))
    }

    @Test fun notYetLogged() {
        assertEquals("Rowing machine · not logged yet", DayText.workout(DayText.BLANK_WORKOUT, listOf(ExerciseLine("Rowing machine", true, false, style = row))))
    }

    @Test fun mixedDaysCountExercisesAndAddTheCardio() {
        val lines = listOf(
            ExerciseLine("Rowing machine", cardio = true, done = true, distanceM = 3260, durationS = 907.0, style = row),
            ExerciseLine("Bench press", cardio = false, done = true),
            ExerciseLine("Row", cardio = false, done = false),
        )
        assertEquals("Push A · 2 of 3 exercises · 3,260 m cardio", DayText.workout("Push A", lines))
        val noRowYet = lines.map { if (it.cardio) it.copy(done = false, distanceM = null) else it }
        assertEquals("Push A · 1 of 3 exercises", DayText.workout("Push A", noRowYet))
        val run = lines.map { if (it.cardio) it.copy(name = "Run", distanceM = 5000, style = Cardio.Style.RUN_MI) else it }
        assertEquals("Push A · 2 of 3 exercises · 3.1 mi cardio", DayText.workout("Push A", run))
        val elliptical = lines.map { if (it.cardio) it.copy(name = "Elliptical", distanceM = null, durationS = 1800.0, style = Cardio.Style.TIME) else it }
        assertEquals("Push A · 2 of 3 exercises · 30 min cardio", DayText.workout("Push A", elliptical))
    }

    /** Someone who never logs cardio sees exactly the line they saw before cardio existed. */
    @Test fun liftingOnlyLinesAreUnchanged() {
        val names = listOf("Bench press", "Squat", "Row", "Deadlift")
        for (size in 0..names.size) for (done in 0..size) {
            val lines = names.take(size).mapIndexed { i, n -> ExerciseLine(n, cardio = false, done = i < done) }
            listOf("Push A", DayText.BLANK_WORKOUT).forEach { title ->
                assertEquals("$title $done/$size", DayText.workout(title, done, size), DayText.workout(title, lines))
            }
        }
        assertEquals(null, DayText.cardioTotal(emptyList()))
    }

    @Test fun titles() {
        assertEquals("Rowing machine", DayText.workoutTitle(DayText.BLANK_WORKOUT, 1, "Rowing machine", 1))
        assertEquals(DayText.BLANK_WORKOUT, DayText.workoutTitle(DayText.BLANK_WORKOUT, 2, "Rowing machine", 1))
        assertEquals("Leg day", DayText.workoutTitle("Leg day", 1, "Run", 1))
        assertEquals("Rowing machine", DayText.shortName("Rowing machine (erg)"))
        assertEquals("(erg)", DayText.shortName("(erg)"))
    }
}
