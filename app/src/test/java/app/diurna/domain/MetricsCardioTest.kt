package app.diurna.domain

import app.diurna.data.ExerciseEntity
import app.diurna.data.MetricsRepository
import app.diurna.data.SetRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MetricsCardioTest {
    @Test fun cardioIsNeverLiftingVolume() {
        val d = LocalDate.of(2026, 10, 1)
        val entries = listOf(
            Metrics.SetEntry(d, 1, "Bench", 1, 100.0, 5),
            // A cardio set never has weight and reps, but even if one did it isn't volume or e1RM.
            Metrics.SetEntry(d, 2, "Rowing machine", 2, 100.0, 5, cardio = true),
        )
        val s = Metrics.sessions(entries).single()
        assertEquals(1L, s.exerciseId)
        assertEquals(500.0, s.volume, 1e-9)
    }

    @Test fun repositorySplitsLiftingFromCardio() {
        val rows = listOf(
            SetRow("2026-10-01", 1, "Bench", 1, 100.0, 5),
            SetRow("2026-10-01", 2, "Rowing machine", 2, null, null, ExerciseEntity.KIND_CARDIO, "500m", 3260, 907.0, 130.0, 104.0, 158, 120),
            SetRow("2026-10-02", 2, "Rowing machine", 3, null, null, ExerciseEntity.KIND_CARDIO, "500m"), // empty piece
            SetRow("2026-10-03", 4, "Elliptical", 4, null, null, ExerciseEntity.KIND_CARDIO, "time", durationS = 1800.0, kcal = 250),
        )
        val t = MetricsRepository.split(rows)
        assertEquals(listOf(1L), t.lifting.map { it.exerciseId })
        val (row, elliptical) = t.cardio
        assertEquals(3260, row.distanceM)
        assertEquals(130.0, row.splitS!!, 1e-9)
        assertEquals(Cardio.Style.ROW, row.style)
        assertEquals(Cardio.Style.TIME, elliptical.style)
        assertEquals(250, elliptical.kcal)
    }

    /** No cardio rows means no cardio sessions (so no Cardio card) and the same lifting as before. */
    @Test fun liftingOnlyHistoryHasNoCardio() {
        val rows = listOf(
            SetRow("2026-10-01", 1, "Bench", 1, 100.0, 5),
            SetRow("2026-10-01", 1, "Bench", 1, 105.0, 3),
            SetRow("2026-10-03", 3, "Squat", 5, 225.0, 5),
            SetRow("2026-10-03", 3, "Squat", 5, null, null),
        )
        val t = MetricsRepository.split(rows)
        assertTrue(t.cardio.isEmpty())
        val before = Metrics.sessions(rows.map { Metrics.SetEntry(LocalDate.parse(it.date), it.exerciseId, it.name, it.loggedId, it.weight, it.reps) })
        assertEquals(before, t.lifting)
    }
}
