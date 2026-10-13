package app.daybook.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Once a day is over, freezes what it was judged against: its alarm, planned workout and
 * targets. Later edits to the routine or profile then only affect days still to come.
 *
 * Idempotent and catch-up based: every past day without a snapshot gets one, including days with
 * no data (so a missed morning stays scored against that day's alarm). Safe to call from
 * anywhere; it runs at startup, on boot, from the alarm and wallpaper ticks, and before any
 * routine or profile edit.
 */
class DayFinalizer(
    private val db: AppDatabase,
    private val days: DayRepository,
    private val nutrition: NutritionRepository,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val mutex = Mutex()
    private val dao = db.dayDao()

    suspend fun catchUp() = mutex.withLock {
        val yesterday = now().toLocalDate().minusDays(1)
        val dates = sortedSetOf<LocalDate>()
        // Every day from the last snapshot (or the first day ever) up to yesterday...
        val from = dao.lastFinalizedDate()?.let { LocalDate.parse(it).plusDays(1) }
            ?: dao.firstDate()?.let(LocalDate::parse)
        if (from != null) {
            var d = maxOf(from, yesterday.minusDays(MAX_CATCH_UP_DAYS))
            while (!d.isAfter(yesterday)) { dates += d; d = d.plusDays(1) }
        }
        // ...plus older days that got their first row later (e.g. backfilled food).
        dao.unfinalizedThrough(yesterday.key()).mapTo(dates, LocalDate::parse)
        dates.forEach { finalize(it) }
    }

    private suspend fun finalize(date: LocalDate) {
        dao.insertIgnore(DayEntity(date.key()))
        val routineDay = db.routineDao().getDay(date.dayOfWeek.value)
        val planned = routineDay?.templateId?.let { db.routineDao().getTemplate(it)?.name }
        val targets = nutrition.computeTargets(date)
        dao.finalize(
            date.key(),
            alarm = days.effectiveAlarm(date), // already the frozen value if one was armed
            planned = planned,
            kcal = targets?.kcal, protein = targets?.proteinG, carbs = targets?.carbsG, fat = targets?.fatG,
        )
    }

    private companion object {
        /** Bounds the first run on a long-idle install. */
        const val MAX_CATCH_UP_DAYS = 400L
    }
}
