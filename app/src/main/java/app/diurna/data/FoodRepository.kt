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

    /** Called when an edit re-estimates an entry, with a count-only reason for the debug log. */
    var onReestimate: (details: Map<String, String>) -> Unit = {}

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
     * Changing the text re-estimates an entry; so does adding, removing or reordering any of an AI
     * entry's photos, since the estimate reads them all (see [EditReestimate]). Saved-meal entries keep
     * their exact numbers when only photos change. Orphaned files are deleted.
     */
    suspend fun edit(id: Long, text: String, time: String) {
        val old = food.get(id) ?: return
        edit(old, text, time, gallery(old))
    }

    /** An entry's photos in gallery order; entries from before the gallery have only their primary photo. */
    suspend fun gallery(id: Long): List<String> = food.get(id)?.let { gallery(it) }.orEmpty()

    private suspend fun gallery(entry: FoodEntryEntity): List<String> =
        photoStore.forEntry(entry.id).map { it.fileName }.ifEmpty { listOfNotNull(entry.photo) }

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
        val reestimate = EditReestimate.decide(old, newText, before = gallery(old), after = capped)
        if (reestimate != null) {
            food.edit(id, newText, time, capped.firstOrNull())
        } else if (time != old.time) {
            food.setTime(id, time)
        }
        // Also keeps food_entry.photo in step with the gallery's first photo (saved meals included).
        photoStore.replaceAll(FoodPhotoEntity.OWNER_ENTRY, id, capped)
        if (reestimate != null) {
            onReestimate(reestimate.details())
            onPendingEstimates()
        }
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
