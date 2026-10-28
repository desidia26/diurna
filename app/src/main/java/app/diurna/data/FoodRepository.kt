package app.diurna.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

class FoodRepository(
    private val db: AppDatabase,
    val photos: FoodPhotos,
    val photoStore: FoodPhotoStore,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val food = db.foodDao()

    /** Called when entries are waiting for an estimate; the container hooks the background worker here. */
    var onPendingEstimates: () -> Unit = {}

    fun observe(date: LocalDate): Flow<List<FoodEntryEntity>> = food.observe(date.key())

    fun observePhotos(entryId: Long): Flow<List<FoodPhotoEntity>> = photoStore.observeEntry(entryId)

    /** Today's entries get the current time; backfilled ones default to noon. Photos are already in [photos]. */
    suspend fun add(date: LocalDate, text: String, photo: String? = null) =
        add(date, text, listOfNotNull(photo))

    suspend fun add(date: LocalDate, text: String, photoNames: List<String>) {
        val t = now()
        db.dayDao().insertIgnore(DayEntity(date.key()))
        val primary = photoNames.firstOrNull()
        val id = food.insert(
            FoodEntryEntity(
                date = date.key(),
                time = if (date == t.toLocalDate()) t.hhmm() else "12:00",
                text = text.trim(),
                loggedAt = System.currentTimeMillis(),
                photo = primary,
            )
        )
        if (photoNames.isNotEmpty()) {
            photoStore.replaceAll(FoodPhotoEntity.OWNER_ENTRY, id, photoNames)
        }
        onPendingEstimates()
    }

    /**
     * Changing the text or the primary photo re-estimates it; that's how a wrong estimate gets corrected.
     * Secondary gallery photos can change without re-estimating. Orphaned files are deleted.
     */
    suspend fun edit(id: Long, text: String, time: String) {
        val old = food.get(id) ?: return
        val gallery = photoStore.forEntry(id).map { it.fileName }
            .ifEmpty { listOfNotNull(old.photo) }
        edit(old, text, time, gallery)
    }

    suspend fun edit(id: Long, text: String, time: String, photo: String?) {
        edit(food.get(id) ?: return, text, time, listOfNotNull(photo))
    }

    suspend fun edit(id: Long, text: String, time: String, photoNames: List<String>) {
        edit(food.get(id) ?: return, text, time, photoNames)
    }

    private suspend fun edit(old: FoodEntryEntity, text: String, time: String, photoNames: List<String>) {
        val id = old.id
        val newText = text.trim()
        if (newText.isEmpty()) return
        val capped = photoNames.distinct().take(FoodPhotoEntity.MAX_PER_OWNER)
        val primary = capped.firstOrNull()
        val primaryChanged = primary != old.photo
        when {
            newText != old.text -> food.edit(id, newText, time, primary)
            primaryChanged && old.isMeal -> {
                // A saved meal's numbers are exact; photos are just for the record.
                if (time != old.time) food.setTime(id, time)
            }
            primaryChanged -> food.edit(id, newText, time, primary)
            else -> {
                if (time != old.time) food.setTime(id, time)
            }
        }
        photoStore.replaceAll(FoodPhotoEntity.OWNER_ENTRY, id, capped)
        if (newText != old.text || (primaryChanged && !old.isMeal)) onPendingEstimates()
    }

    /** Clears recorded failures (e.g. after adding a key) so those entries are tried again. */
    suspend fun retryFailedEstimates() {
        food.clearEstimateErrors()
        onPendingEstimates()
    }

    suspend fun delete(id: Long) {
        photoStore.deleteOwner(FoodPhotoEntity.OWNER_ENTRY, id)
        food.delete(id)
    }

    /** Removes photo files no entry or meal gallery uses any more (see [FoodPhotos.sweep]). */
    suspend fun sweepPhotos(): Int {
        val inUse = photoStore.allInUse()
        return withContext(Dispatchers.IO) { photos.sweep(inUse) }
    }
}
