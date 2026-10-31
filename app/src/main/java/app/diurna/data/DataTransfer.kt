package app.diurna.data

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.diurna.domain.ExportArchive
import app.diurna.domain.ExportArchive.Kind
import app.diurna.domain.ExportArchive.Problem
import app.diurna.domain.PhotoSizing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ImportFailed(val problem: Problem) : Exception(problem.toString())

/**
 * Everything in one zip and back: the database, settings (with the porch code) and meal photos,
 * for moving to a new install, such as one signed with a different key. API keys stay out: they're
 * encrypted to this install, and an export may sit in Downloads or a cloud drive.
 *
 * An import is staged and checked while the app runs, then swapped in by [ImportApply] on the next
 * start, before Room or DataStore open their files.
 */
class DataTransfer(
    private val backups: Backups,
    private val photos: FoodPhotos,
    private val versionCode: Int,
    private val scratchDir: File,
    val stagingDir: File,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    /** The schema the running app is at; exports record it and imports must not be newer. */
    private val schema: Int get() = backups.schema

    suspend fun export(out: OutputStream) = backups.exclusive {
        withContext(Dispatchers.IO) {
            val db = File(scratchDir, "export.db")
            try {
                backups.snapshot(db)
                ZipOutputStream(out.buffered()).use { zip ->
                    val manifest = ExportArchive.Manifest(ExportArchive.FORMAT, schema, versionCode, now().format(STAMP))
                    zip.putNextEntry(ZipEntry(ExportArchive.MANIFEST))
                    zip.write(ExportArchive.manifestJson(manifest).toByteArray())
                    zip.add(ExportArchive.DATABASE, db)
                    backups.settingsFile.takeIf { it.isFile }?.let { zip.add(ExportArchive.SETTINGS, it) }
                    photos.dir.listFiles().orEmpty()
                        .filter { it.isFile && PhotoSizing.FILE_NAME.matches(it.name) && it.length() <= PhotoSizing.MAX_BYTES }
                        .forEach { zip.add(ExportArchive.PHOTO_DIR + it.name, it) }
                }
            } finally {
                db.delete()
            }
        }
    }

    data class Staged(val manifest: ExportArchive.Manifest, val days: Int)

    /**
     * Unpacks [input] into [stagingDir] and checks it, throwing [ImportFailed] if it isn't an
     * export this app can take. Nothing outside [stagingDir] is touched; [commit] does the rest.
     */
    suspend fun stage(input: InputStream): Staged = withContext(Dispatchers.IO) {
        stagingDir.deleteRecursively()
        try {
            stageOrThrow(input)
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw if (e is ImportFailed) e else ImportFailed(Problem.Damaged).also { Log.w(TAG, "import unreadable", e) }
        }
    }

    private fun stageOrThrow(input: InputStream): Staged {
        File(stagingDir, ExportArchive.PHOTO_DIR).mkdirs()
        val seen = mutableSetOf<String>()
        var total = 0L
        var photoCount = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val kind = ExportArchive.kindOf(entry.name)
                if (entry.isDirectory || kind == null || !seen.add(entry.name)) throw ImportFailed(Problem.NotAnExport)
                if (kind == Kind.PHOTO && ++photoCount > ExportArchive.MAX_PHOTOS) throw ImportFailed(Problem.NotAnExport)
                total += copyCapped(zip, File(stagingDir, entry.name), ExportArchive.maxBytes(kind), ExportArchive.MAX_TOTAL - total)
            }
        }
        val manifest = File(stagingDir, ExportArchive.MANIFEST).takeIf { it.isFile }
            ?.let { ExportArchive.parseManifest(it.readText()) }
        ExportArchive.check(manifest, schema)?.let { throw ImportFailed(it) }
        val dbFile = File(stagingDir, ExportArchive.DATABASE).takeIf { it.isFile } ?: throw ImportFailed(Problem.NotAnExport)
        val days = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val ok = db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
            if (!ok || db.version != manifest!!.schema) throw ImportFailed(Problem.Damaged)
            val hasDays = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'day'", null).use { it.moveToFirst() }
            if (!hasDays) throw ImportFailed(Problem.NotAnExport)
            db.rawQuery("SELECT COUNT(*) FROM day", null).use { it.moveToFirst(); it.getInt(0) }
        }
        return Staged(manifest!!, days)
    }

    /** Copies at most [cap] bytes (and no more than [budget]); a longer entry fails the import. */
    private fun copyCapped(input: InputStream, to: File, cap: Long, budget: Long): Long {
        val limit = minOf(cap, budget)
        var written = 0L
        val buf = ByteArray(64 * 1024)
        to.outputStream().use { out ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                written += n
                if (written > limit) throw ImportFailed(Problem.NotAnExport)
                out.write(buf, 0, n)
            }
        }
        return written
    }

    /** Marks the staged import ready; [ImportApply] swaps it in on the next start. */
    suspend fun commit() = withContext(Dispatchers.IO) {
        backups.backupNow() // what's here now, in case the import was the wrong file
        File(stagingDir, ImportApply.READY).writeText(now().format(STAMP))
    }

    fun discard() {
        stagingDir.deleteRecursively()
    }

    private fun ZipOutputStream.add(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    companion object {
        private const val TAG = "DiurnaTransfer"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        fun fileName(now: LocalDateTime = LocalDateTime.now()): String =
            "diurna-export-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.zip"

        /** Starts the app afresh so [ImportApply] runs before anything opens the database. */
        fun restart(context: Context) {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            context.startActivity(Intent.makeRestartActivityTask(launch.component))
            Runtime.getRuntime().exit(0)
        }
    }
}

/**
 * Swaps a staged import into place at startup, before the database or settings are opened. What
 * was there before goes to [undoDir] and is put back if the swap fails partway.
 */
class ImportApply(
    private val stagingDir: File,
    private val dbFile: File,
    private val settingsFile: File,
    private val photosDir: File,
    private val undoDir: File,
) {
    /** True if an import was applied. A staging folder that was never committed is cleared. */
    fun applyPending(): Boolean {
        if (!stagingDir.exists()) return false
        if (!File(stagingDir, READY).isFile) {
            stagingDir.deleteRecursively()
            return false
        }
        undoDir.deleteRecursively()
        undoDir.mkdirs()
        val current = listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"), settingsFile, photosDir)
        return try {
            current.filter { it.exists() }.forEach { move(it, File(undoDir, it.name)) }
            move(File(stagingDir, ExportArchive.DATABASE), dbFile)
            File(stagingDir, ExportArchive.SETTINGS).takeIf { it.exists() }?.let { move(it, settingsFile) }
            move(File(stagingDir, ExportArchive.PHOTO_DIR), photosDir)
            stagingDir.deleteRecursively()
            true
        } catch (e: Exception) {
            Log.e(TAG, "import failed, putting the old data back", e)
            listOf(dbFile, settingsFile).forEach { it.delete() }
            photosDir.deleteRecursively()
            current.forEach { f -> File(undoDir, f.name).takeIf { it.exists() }?.let { runCatching { move(it, f) } } }
            stagingDir.deleteRecursively()
            false
        }
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) throw java.io.IOException("couldn't move ${from.name}")
    }

    companion object {
        private const val TAG = "DiurnaTransfer"
        const val READY = "ready"
        const val STAGING = "import-staging"
        private const val PREFS = "import"
        private const val IMPORTED = "imported_unseen"

        fun markImported(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(IMPORTED, true).apply()

        /** Whether an import was applied that Setup hasn't mentioned yet; asking clears it. */
        fun takeImported(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getBoolean(IMPORTED, false).also { if (it) prefs.edit().remove(IMPORTED).apply() }
        }

        fun forApp(context: Context) = ImportApply(
            stagingDir = File(context.filesDir, STAGING),
            dbFile = context.getDatabasePath(AppDatabase.NAME),
            settingsFile = File(context.filesDir, "datastore/settings.preferences_pb"),
            photosDir = File(context.filesDir, "food-photos"),
            undoDir = File(context.filesDir, "pre-import"),
        )
    }
}
