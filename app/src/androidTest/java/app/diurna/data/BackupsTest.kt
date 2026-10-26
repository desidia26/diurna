package app.diurna.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

@RunWith(AndroidJUnit4::class)
class BackupsTest {
    private val t = TestDb()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Its own folder, apart from the app's real backups and the daily job that writes them.
    private val dir = java.io.File(context.getExternalFilesDir(null), "backups-test-${System.nanoTime()}")
    private val backups = Backups(context, t.db, t.food.photos, dir)

    @After fun tearDown() {
        t.close()
        dir.deleteRecursively()
    }

    @Test fun backupIsACompleteReadableCopy() = runTest {
        t.food.add(t.mon, "eggs")
        t.food.add(t.mon, "toast")
        val file = backups.backupNow()
        assertNotNull(file)
        assertTrue(file!!.parentFile!!.path.contains("Android/data"))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
            copy.rawQuery("SELECT COUNT(*) FROM food_entry", null).use { it.moveToFirst(); assertEquals(2, it.getInt(0)) }
            copy.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); assertEquals("ok", it.getString(0)) }
        }
        assertNotNull("Setup shows when it happened", backups.last.value)
    }

    // 16 real backups a second apart: past runTest's 1-minute default on a slow, unaccelerated emulator.
    @Test fun keepsOnlyTheNewestFourteen() = runTest(timeout = 5.minutes) {
        repeat(16) { backups.backupNow(); Thread.sleep(1_100) } // names are per-second
        val kept = backups.dir.listFiles { f -> f.name.startsWith("daybook-") }!!.size
        assertEquals(14, kept)
    }

    @Test fun photosAreMirroredAndKeptWhileABackupUsesThem() = runTest {
        val name = t.food.photos.newName()
        java.io.File(t.photoDir, name).writeBytes(byteArrayOf(1, 2, 3))
        t.food.add(t.mon, "pasta", name)
        backups.backupNow()
        val mirrored = java.io.File(dir, "food-photos/$name")
        assertTrue(mirrored.exists())

        // Deleted in the app, but the backup just taken still points at it.
        t.food.delete(t.food.observe(t.mon).first().single().id)
        Thread.sleep(1_100) // backup names are per-second: a second backup in the same second replaces the first
        backups.backupNow()
        assertTrue(mirrored.exists())
    }
}
