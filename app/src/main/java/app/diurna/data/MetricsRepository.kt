package app.diurna.data

import app.diurna.domain.Cardio
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
    data class Snapshot(
        val from: LocalDate,
        val to: LocalDate,
        val days: List<DayEntity>,
        val sessions: List<Metrics.Session>,
        val cardio: List<Cardio.Session> = emptyList(),
    )

    data class Training(val lifting: List<Metrics.Session>, val cardio: List<Cardio.Session>)

    /** Every lifting session ever logged; PRs are judged against all of it. */
    val sessions: Flow<List<Metrics.Session>> get() = training.map { it.lifting }

    /** Lifting and cardio sessions, each judged against all of their own history. */
    val training: Flow<Training> = workouts.observeSetRows().map(::split)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(range: Metrics.Range, today: LocalDate): Flow<Snapshot> =
        days.observeFirstDate().map { it?.let(LocalDate::parse) }.distinctUntilChanged().flatMapLatest { first ->
            val from = range.start(today, first)
            combine(days.observeRange(from.minusDays(6).key(), today.key()), training) { rows, t ->
                Snapshot(from, today, rows, t.lifting, t.cardio)
            }
        }

    companion object {
        fun split(rows: List<SetRow>): Training {
            val lifting = Metrics.sessions(rows.map {
                Metrics.SetEntry(LocalDate.parse(it.date), it.exerciseId, it.name, it.loggedId, it.weight, it.reps, cardio = it.kind == ExerciseEntity.KIND_CARDIO)
            })
            val cardio = Cardio.sessions(rows.filter { it.kind == ExerciseEntity.KIND_CARDIO }.map {
                Cardio.Piece(
                    LocalDate.parse(it.date), it.exerciseId, it.name, it.loggedId,
                    it.distanceM, it.durationS, it.splitS, it.bestSplitS, it.watts, it.kcal, it.avgHr,
                    Cardio.Style.of(it.cardioStyle),
                )
            })
            return Training(lifting, cardio)
        }
    }
}
