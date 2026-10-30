package app.diurna.domain

import org.json.JSONException
import org.json.JSONObject

/**
 * What an export zip may hold, and how big. An import is a file from outside the app (it may have
 * been edited, truncated or swapped), so every entry is checked by name and size before anything
 * is written, and anything unexpected stops the import.
 */
object ExportArchive {
    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val DATABASE = "daybook.db"
    const val SETTINGS = "settings.preferences_pb"
    const val PHOTO_DIR = "food-photos/"

    const val MAX_DATABASE = 256L * 1024 * 1024
    const val MAX_SETTINGS = 1L * 1024 * 1024
    const val MAX_MANIFEST = 4L * 1024
    const val MAX_PHOTOS = 5_000
    const val MAX_TOTAL = 1024L * 1024 * 1024

    enum class Kind { MANIFEST, DATABASE, SETTINGS, PHOTO }

    /**
     * The kind of a zip entry, or null if an export never contains it. Only exact names match, so
     * paths like "../x", "/x" or "food-photos/../x" are refused rather than cleaned up.
     */
    fun kindOf(name: String): Kind? = when {
        name == MANIFEST -> Kind.MANIFEST
        name == DATABASE -> Kind.DATABASE
        name == SETTINGS -> Kind.SETTINGS
        name.startsWith(PHOTO_DIR) && PhotoSizing.FILE_NAME.matches(name.removePrefix(PHOTO_DIR)) -> Kind.PHOTO
        else -> null
    }

    fun maxBytes(kind: Kind): Long = when (kind) {
        Kind.MANIFEST -> MAX_MANIFEST
        Kind.DATABASE -> MAX_DATABASE
        Kind.SETTINGS -> MAX_SETTINGS
        Kind.PHOTO -> PhotoSizing.MAX_BYTES.toLong()
    }

    data class Manifest(val format: Int, val schema: Int, val versionCode: Int, val exportedAt: String)

    fun manifestJson(m: Manifest): String = JSONObject()
        .put("format", m.format).put("schema", m.schema).put("versionCode", m.versionCode).put("exportedAt", m.exportedAt)
        .toString()

    /** The manifest, or null if it isn't one this app wrote. */
    fun parseManifest(json: String): Manifest? = try {
        val o = JSONObject(json)
        Manifest(o.getInt("format"), o.getInt("schema"), o.getInt("versionCode"), o.getString("exportedAt").take(40))
    } catch (_: JSONException) {
        null
    }

    sealed interface Problem {
        data object NotAnExport : Problem
        data object Newer : Problem
        data object Damaged : Problem
    }

    /** Whether an export with this manifest can be imported into an app at [currentSchema]. */
    fun check(m: Manifest?, currentSchema: Int): Problem? = when {
        m == null -> Problem.NotAnExport
        m.format > FORMAT || m.schema > currentSchema -> Problem.Newer
        m.format < 1 || m.schema < 1 -> Problem.NotAnExport
        else -> null
    }
}
