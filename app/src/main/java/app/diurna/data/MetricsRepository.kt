package app.diurna.data

import app.diurna.domain.Metrics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** Read-only source for the Metrics tab. Nothing is stored for metrics: they're worked out from current data. */
class MetricsRepository(db: AppDatabase) {
    private val days = db.dayDao()
    private val workouts = db.workoutDao()

    /** The range's days, plus the 6 before [from] so the first 7-day averages have a full window. */
    data class Snapshot(val from: LocalDate, val to: LocalDate, val days: List<DayEntity>, val sessions: List<Metrics.Session>)

    /** Every lifting session ever logged; PRs are judged against all of it. */
    val sessions: Flow<List<Metrics.Session>> = workouts.observeSetRows().map { rows ->
        Metrics.sessions(rows.map { Metrics.SetEntry(LocalDate.parse(it.date), it.exerciseId, it.name, it.loggedId, it.weight, it.reps) })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(range: Metrics.Range, today: LocalDate): Flow<Snapshot> =
        days.observeFirstDate().map { it?.let(LocalDate::parse) }.distinctUntilChanged().flatMapLatest { first ->
            val from = range.start(today, first)
            combine(days.observeRange(from.minusDays(6).key(), today.key()), sessions) { rows, s ->
                Snapshot(from, today, rows, s)
            }
        }
}
