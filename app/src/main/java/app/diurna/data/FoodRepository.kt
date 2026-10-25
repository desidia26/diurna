package app.diurna.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

class FoodRepository(
    private val db: AppDatabase,
    val photos: FoodPhotos,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val food = db.foodDao()

    /** Called when entries are waiting for an estimate; the container hooks the background worker here. */
    var onPendingEstimates: () -> Unit = {}

    fun observe(date: LocalDate): Flow<List<FoodEntryEntity>> = food.observe(date.key())

    /** Today's entries get the current time; backfilled ones default to noon. [photo] is already in [photos]. */
    suspend fun add(date: LocalDate, text: String, photo: String? = null) {
        val t = now()
        db.dayDao().insertIgnore(DayEntity(date.key()))
        food.insert(
            FoodEntryEntity(
                date = date.key(),
                time = if (date == t.toLocalDate()) t.hhmm() else "12:00",
                text = text.trim(),
                loggedAt = System.currentTimeMillis(),
                photo = photo,
            )
        )
        onPendingEstimates()
    }

    /**
     * Changing the text or the photo re-estimates it; that's how a wrong estimate gets corrected.
     * A replaced or removed photo's file is deleted once the entry no longer points at it.
     */
    suspend fun edit(id: Long, text: String, time: String) {
        val old = food.get(id) ?: return
        edit(old, text, time, old.photo)
    }

    suspend fun edit(id: Long, text: String, time: String, photo: String?) {
        edit(food.get(id) ?: return, text, time, photo)
    }

    private suspend fun edit(old: FoodEntryEntity, text: String, time: String, photo: String?) {
        val id = old.id
        val newText = text.trim()
        if (newText.isEmpty()) return
        when {
            newText != old.text -> food.edit(id, newText, time, photo)
            photo != old.photo && old.isMeal -> {
                // A saved meal's numbers are exact; the photo is just for the record.
                food.setPhoto(id, photo)
                if (time != old.time) food.setTime(id, time)
            }
            photo != old.photo -> food.edit(id, newText, time, photo)
            else -> {
                if (time != old.time) food.setTime(id, time) // same food, so the estimate still holds
                return
            }
        }
        if (old.photo != photo) withContext(Dispatchers.IO) { photos.delete(old.photo) }
        if (newText != old.text || !old.isMeal) onPendingEstimates()
    }

    /** Clears recorded failures (e.g. after adding a key) so those entries are tried again. */
    suspend fun retryFailedEstimates() {
        food.clearEstimateErrors()
        onPendingEstimates()
    }

    suspend fun delete(id: Long) {
        val photo = food.get(id)?.photo
        food.delete(id)
        withContext(Dispatchers.IO) { photos.delete(photo) }
    }

    /** Removes photo files no entry uses any more (see [FoodPhotos.sweep]). */
    suspend fun sweepPhotos(): Int {
        val inUse = food.photosInUse().toSet()
        return withContext(Dispatchers.IO) { photos.sweep(inUse) }
    }
}
