package app.diurna.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.diurna.domain.ExportArchive
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DataTransferTest {
    private val t = TestDb()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Everything under one throwaway folder, apart from the app's real database, settings and photos.
    private val root = File(context.cacheDir, "transfer-test-${System.nanoTime()}").apply { mkdirs() }
    private val backups = Backups(context, t.db, t.foodPhotos, File(root, "backups"))
    private val transfer = DataTransfer(backups, t.foodPhotos, versionCode = 1, scratchDir = root, stagingDir = File(root, "staging"))
    private val target: File = File(root, "target")
    private val swap = ImportApply(
        stagingDir = File(root, "staging"),
        dbFile = File(target, "databases/daybook.db"),
        settingsFile = File(target, "datastore/settings.preferences_pb"),
        photosDir = File(target, "food-photos"),
        undoDir = File(target, "pre-import"),
    )

    @After fun tearDown() {
        t.close()
        root.deleteRecursively()
    }

    private suspend fun exported(): ByteArray = ByteArrayOutputStream().also { transfer.export(it) }.toByteArray()

    @Test fun exportThenImportBringsBackRowsAndPhotos() = runTest {
        val photo = t.food.photos.newName()
        File(t.photoDir, photo).writeBytes(byteArrayOf(1, 2, 3))
        t.food.add(t.mon, "eggs")
        t.food.add(t.mon, "pasta", photo)
        // What the new install had before the import goes aside, not away.
        File(target, "databases").mkdirs()
        File(target, "databases/daybook.db").writeText("old")

        val staged = transfer.stage(ByteArrayInputStream(exported()))
        assertEquals(backups.schema, staged.manifest.schema)
        assertFalse("not applied until committed", swap.applyPending())
        transfer.stage(ByteArrayInputStream(exported()))
        transfer.commit()
        assertTrue(swap.applyPending())

        SQLiteDatabase.openDatabase(File(target, "databases/daybook.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM food_entry", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
        }
        assertTrue(File(target, "food-photos/$photo").readBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("old", File(target, "pre-import/daybook.db").readText())
        assertFalse(File(root, "staging").exists())
    }

    @Test fun refusesEntriesOutsideTheExport() = runTest {
        val zip = zipOf(ExportArchive.MANIFEST to manifest(backups.schema), "../evil" to "x")
        assertProblem(ExportArchive.Problem.NotAnExport, zip)
    }

    @Test fun refusesExportsFromNewerVersions() = runTest {
        assertProblem(ExportArchive.Problem.Newer, zipOf(ExportArchive.MANIFEST to manifest(backups.schema + 1)))
    }

    @Test fun refusesADamagedDatabase() = runTest {
        val zip = zipOf(ExportArchive.MANIFEST to manifest(backups.schema), ExportArchive.DATABASE to "not a database")
        assertProblem(ExportArchive.Problem.Damaged, zip)
    }

    private suspend fun assertProblem(problem: ExportArchive.Problem, zip: ByteArray) {
        try {
            transfer.stage(ByteArrayInputStream(zip))
            fail("expected $problem")
        } catch (e: ImportFailed) {
            assertEquals(problem, e.problem)
        }
        assertFalse("nothing is left staged", File(root, "staging").exists())
        assertFalse(swap.applyPending())
        assertFalse(target.exists())
    }

    private fun manifest(schema: Int) =
        ExportArchive.manifestJson(ExportArchive.Manifest(ExportArchive.FORMAT, schema, 1, "2026-10-28T09:30:00"))

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()) }
        }
    }.toByteArray()
}
