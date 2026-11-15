package app.diurna.ai

import app.diurna.data.AppDatabase
import app.diurna.data.FoodEntryEntity
import app.diurna.data.FoodPhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every photo of a food entry, in gallery order, as JPEG bytes for its estimate. Entries from
 * before the gallery have only [FoodEntryEntity.photo]. Photos that have gone missing are skipped.
 */
internal object EntryPhotos {
    fun names(gallery: List<String>, primary: String?): List<String> =
        gallery.ifEmpty { listOfNotNull(primary) }.distinct().take(VisionSupport.MAX_ESTIMATE_PHOTOS)

    suspend fun read(db: AppDatabase, entry: FoodEntryEntity, photo: (String) -> ByteArray?): List<ByteArray> {
        val gallery = db.foodPhotoDao().forOwner(FoodPhotoEntity.OWNER_ENTRY, entry.id).map { it.fileName }
        val names = names(gallery, entry.photo)
        return withContext(Dispatchers.IO) { names.mapNotNull { photo(it) } }
    }
}
