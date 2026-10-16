package app.daybook.data

import app.daybook.domain.StepMath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime

/** The phone's step sensor: its running total since boot, or null if unavailable or not permitted. */
interface CounterSensor {
    suspend fun read(): Long?
}

/** Remembers the sensor's value at the last look (kept in settings, outside the database). */
interface StepStateStore {
    suspend fun loadStepState(): StepMath.State?
    suspend fun saveStepState(state: StepMath.State)
}

/**
 * Looks at the step sensor and adds new steps to the day they belong to, per [StepMath]. Each
 * day's running total lives on its day row; finished days keep theirs frozen. Looking is cheap,
 * so it happens every 15 minutes, just before midnight, at boot, and whenever today's count is read.
 */
class StepCounter(
    private val db: AppDatabase,
    private val sensor: CounterSensor,
    private val store: StepStateStore,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val bootedAt: () -> LocalDateTime? = { null },
) : StepSource {
    private val mutex = Mutex()
    private val days = db.dayDao()

    suspend fun sample() = mutex.withLock {
        val counter = sensor.read() ?: return@withLock
        val r = StepMath.look(store.loadStepState(), counter, now(), bootedAt())
        store.saveStepState(r.next)
        val key = r.date.key()
        // Write only when something changed, so idle looks don't trigger redraws everywhere.
        if (r.add > 0 || days.get(key)?.steps == null) {
            days.insertIgnore(DayEntity(key))
            days.addSteps(key, r.add)
        }
    }

    /** Today's count takes a fresh look first; other days return what was recorded. */
    override suspend fun stepsOn(date: LocalDate): Long? {
        if (date == now().toLocalDate()) sample()
        return days.get(date.key())?.steps
    }
}
