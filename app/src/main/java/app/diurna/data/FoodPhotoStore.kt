package app.diurna.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Gallery of photos for food entries and saved meals. [FoodEntryEntity.photo] stays the primary
 * (position 0) so AI estimate paths keep a single column; this store owns the full list and
 * deletes files when rows are removed.
 */
class FoodPhotoStore(
    private val db: AppDatabase,
    private val photos: FoodPhotos,
) {
    private val dao = db.foodPhotoDao()
    private val food = db.foodDao()

    fun observeEntry(entryId: Long): Flow<List<FoodPhotoEntity>> =
        dao.observe(FoodPhotoEntity.OWNER_ENTRY, entryId)

    fun observeMeal(mealId: Long): Flow<List<FoodPhotoEntity>> =
        dao.observe(FoodPhotoEntity.OWNER_MEAL, mealId)

    suspend fun forEntry(entryId: Long): List<FoodPhotoEntity> =
        dao.forOwner(FoodPhotoEntity.OWNER_ENTRY, entryId)

    suspend fun forMeal(mealId: Long): List<FoodPhotoEntity> =
        dao.forOwner(FoodPhotoEntity.OWNER_MEAL, mealId)

    /**
     * Replaces the owner's gallery (capped at [FoodPhotoEntity.MAX_PER_OWNER]). For entries,
     * syncs [FoodEntryEntity.photo] to the first file. Deletes files no longer referenced.
     */
    suspend fun replaceAll(ownerType: String, ownerId: Long, fileNames: List<String>) {
        // Validate names via FoodPhotos; file need not exist yet (import race / tests).
        val requested = fileNames.distinct().mapNotNull { photos.file(it)?.name }
        val capped = requested.take(FoodPhotoEntity.MAX_PER_OWNER)
        val oldNames = dao.forOwner(ownerType, ownerId).map { it.fileName }
        db.withTransaction {
            dao.deleteForOwner(ownerType, ownerId)
            if (capped.isNotEmpty()) {
                dao.insertAll(
                    capped.mapIndexed { i, name ->
                        FoodPhotoEntity(
                            ownerType = ownerType,
                            ownerId = ownerId,
                            fileName = name,
                            position = i,
                        )
                    }
                )
            }
            syncPrimary(ownerType, ownerId, capped.firstOrNull())
        }
        val keep = allInUse()
        // Drop previous gallery files and any over-cap extras that never became rows.
        val candidates = (oldNames + requested).distinct()
        withContext(Dispatchers.IO) {
            candidates.filter { it !in keep }.forEach { photos.delete(it) }
        }
    }

    /** Drops gallery rows for an owner and deletes orphaned files. */
    suspend fun deleteOwner(ownerType: String, ownerId: Long) {
        val rows = dao.forOwner(ownerType, ownerId)
        dao.deleteForOwner(ownerType, ownerId)
        if (ownerType == FoodPhotoEntity.OWNER_ENTRY) {
            food.setPhoto(ownerId, null)
        }
        val keep = allInUse()
        withContext(Dispatchers.IO) {
            rows.map { it.fileName }.filter { it !in keep }.forEach { photos.delete(it) }
        }
    }

    suspend fun allInUse(): Set<String> {
        val fromGallery = dao.allFileNames()
        val fromPrimary = food.entryPrimaryPhotos()
        return (fromGallery + fromPrimary).toSet()
    }

    private suspend fun syncPrimary(ownerType: String, ownerId: Long, primary: String?) {
        if (ownerType != FoodPhotoEntity.OWNER_ENTRY) return
        food.setPhoto(ownerId, primary)
    }
}
