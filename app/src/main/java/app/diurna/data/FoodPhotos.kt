package app.diurna.data

import app.diurna.domain.PhotoSizing
import java.io.File
import java.util.UUID

/**
 * Meal photos, one JPEG per entry in app-private storage (files/food-photos). The database keeps
 * only the file name. Names are checked against the pattern this class makes, because a restored
 * or hand-edited database is outside data and must never point outside the folder.
 */
class FoodPhotos(val dir: File) {

    /** A fresh name for a photo about to be written. */
    fun newName(): String = "${UUID.randomUUID()}.jpg"

    /** The file for a stored name, or null if the name isn't one of ours. */
    fun file(name: String?): File? = name?.takeIf { NAME.matches(it) }?.let { File(dir, it) }

    fun exists(name: String?): Boolean = file(name)?.isFile == true

    /** The photo's bytes for an AI request, or null if it's gone, unreadable or oversized. */
    fun read(name: String?): ByteArray? {
        val f = file(name)?.takeIf { it.isFile && it.length() in 1..PhotoSizing.MAX_BYTES.toLong() } ?: return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    fun delete(name: String?) {
        file(name)?.delete()
    }

    /**
     * Deletes photos no entry refers to: attached and then abandoned before logging, or left
     * behind by a database restore. Recent files are kept, since one may be a draft still on screen.
     */
    fun sweep(inUse: Set<String>, now: Long = System.currentTimeMillis(), minAgeMs: Long = DAY_MS): Int {
        val stale = dir.listFiles().orEmpty().filter {
            it.isFile && it.name !in inUse && now - it.lastModified() > minAgeMs
        }
        stale.forEach { it.delete() }
        return stale.size
    }

    companion object {
        private val NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
