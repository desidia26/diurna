package app.diurna.ui

import app.diurna.domain.Cardio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MetricsCardioUiTest {
    private fun session(style: Cardio.Style, distance: Int? = null, time: Double? = null, watts: Int? = null, kcal: Int? = null) =
        Cardio.sessions(listOf(Cardio.Piece(LocalDate.of(2026, 10, 1), 1, "x", 1, distance, time, watts = watts, kcal = kcal, style = style))).single()

    @Test fun metricsFollowTheStyle() {
        assertEquals(listOf(CardioMetric.DISTANCE, CardioMetric.PACE, CardioMetric.POWER), cardioMetrics(Cardio.Style.ROW, emptyList()))
        assertEquals(listOf(CardioMetric.DISTANCE, CardioMetric.PACE), cardioMetrics(Cardio.Style.RUN_MI, emptyList()))
        assertEquals(listOf(CardioMetric.TIME, CardioMetric.KCAL), cardioMetrics(Cardio.Style.TIME, emptyList()))
        // Power shows up where it was logged, even on a style that doesn't ask for it.
        assertEquals(listOf(CardioMetric.POWER, CardioMetric.TIME, CardioMetric.KCAL), cardioMetrics(Cardio.Style.TIME, listOf(session(Cardio.Style.TIME, time = 600.0, watts = 120))))
    }

    @Test fun labelsAndDirection() {
        assertEquals("Split", CardioMetric.PACE.label(Cardio.Style.ROW))
        assertEquals("Pace", CardioMetric.PACE.label(Cardio.Style.RUN_KM))
        assertEquals("Speed", CardioMetric.PACE.label(Cardio.Style.RIDE_MI))
        assertTrue(CardioMetric.PACE.lowerIsBetter(Cardio.Style.RUN_MI))
        assertFalse(CardioMetric.PACE.lowerIsBetter(Cardio.Style.RIDE_KM))
        assertFalse(CardioMetric.DISTANCE.lowerIsBetter(Cardio.Style.ROW))
    }

    @Test fun valuesAreShownInTheStylesUnits() {
        val run = session(Cardio.Style.RUN_KM, 5000, 1560.0)
        assertEquals(5.0, cardioShown(CardioMetric.DISTANCE, Cardio.Style.RUN_KM, run)!!, 1e-9)
        assertEquals(312.0, cardioShown(CardioMetric.PACE, Cardio.Style.RUN_KM, run)!!, 1e-9)
        assertEquals("5.0 km", cardioValue(CardioMetric.DISTANCE, Cardio.Style.RUN_KM, 5.0))
        assertEquals("5:12/km", cardioValue(CardioMetric.PACE, Cardio.Style.RUN_KM, 312.0))
        assertEquals("3,260 m", cardioValue(CardioMetric.DISTANCE, Cardio.Style.ROW, 3260.0))
        assertEquals("2:10/500m", cardioValue(CardioMetric.PACE, Cardio.Style.ROW, 130.0))
        assertEquals("16.2 mph", cardioValue(CardioMetric.PACE, Cardio.Style.RIDE_MI, 16.2))
        assertEquals("30 min", cardioValue(CardioMetric.TIME, Cardio.Style.TIME, 30.0))
        assertEquals("250 kcal", cardioValue(CardioMetric.KCAL, Cardio.Style.TIME, 250.0))
    }
}
