package app.diurna.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupsTest {
    private val t = TestDb()
    private val backups = Backups(InstrumentationRegistry.getInstrumentation().targetContext, t.db)

    @After fun tearDown() = t.close()

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

    @Test fun keepsOnlyTheNewestFourteen() = runTest {
        repeat(16) { backups.backupNow(); Thread.sleep(1_100) } // names are per-second
        val kept = backups.dir.listFiles { f -> f.name.startsWith("daybook-") }!!.size
        assertEquals(14, kept)
    }
}
