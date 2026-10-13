package app.daybook.data

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

class FoodRepository(
    private val db: AppDatabase,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val food = db.foodDao()

    /** Called when entries are waiting for an estimate; the container hooks the background worker here. */
    var onPendingEstimates: () -> Unit = {}

    fun observe(date: LocalDate): Flow<List<FoodEntryEntity>> = food.observe(date.key())

    /** Today's entries get the current time; backfilled ones default to noon. */
    suspend fun add(date: LocalDate, text: String) {
        val t = now()
        db.dayDao().insertIgnore(DayEntity(date.key()))
        food.insert(
            FoodEntryEntity(
                date = date.key(),
                time = if (date == t.toLocalDate()) t.hhmm() else "12:00",
                text = text.trim(),
                loggedAt = System.currentTimeMillis(),
            )
        )
        onPendingEstimates()
    }

    /** Changing the text re-estimates it; that's how a wrong estimate gets corrected. */
    suspend fun edit(id: Long, text: String, time: String) {
        val old = food.get(id) ?: return
        val newText = text.trim()
        if (newText.isEmpty()) return
        if (newText == old.text) {
            if (time != old.time) food.setTime(id, time) // same food, so the estimate still holds
            return
        }
        food.edit(id, newText, time)
        onPendingEstimates()
    }

    /** Clears recorded failures (e.g. after adding a key) so those entries are tried again. */
    suspend fun retryFailedEstimates() {
        food.clearEstimateErrors()
        onPendingEstimates()
    }

    suspend fun delete(id: Long) = food.delete(id)
}
