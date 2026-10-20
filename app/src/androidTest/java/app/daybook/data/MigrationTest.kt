package app.daybook.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every schema change must keep existing history. This builds a v1 database with real-looking
 * data, migrates it, and checks nothing was lost. Add a test like this for every new version.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun v1ToV2KeepsEverything() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL("INSERT INTO routine_day (weekday, alarm_time, template_id) VALUES (2, '06:00', NULL)")
            db.execSQL("INSERT INTO day (date, alarm_override, scheduled_alarm, scanned_at, morning_source, notes) VALUES ('2026-10-12', NULL, '06:00', '06:04', 'scan', NULL)")
            db.execSQL("INSERT INTO exercise (id, name) VALUES (1, 'Bench press')")
            db.execSQL("INSERT INTO day_workout (id, date, name) VALUES (1, '2026-10-12', 'Push')")
            db.execSQL("INSERT INTO logged_exercise (id, workout_id, exercise_id, position) VALUES (1, 1, 1, 0)")
            db.execSQL("INSERT INTO exercise_set (id, logged_exercise_id, position, weight, reps) VALUES (1, 1, 0, 185.0, 8)")
            db.execSQL("INSERT INTO food_entry (id, date, time, text, logged_at, kcal_estimate, estimate_source) VALUES (1, '2026-10-12', '12:30', 'burrito bowl', 1791800000000, NULL, NULL)")
        }

        // Validates the migrated schema matches the exported v2 schema exactly.
        val db = helper.runMigrationsAndValidate(dbName, 2, true)

        db.query("SELECT scanned_at, scheduled_alarm, body_weight, finalized FROM day WHERE date = '2026-10-12'").use {
            assertTrue(it.moveToFirst())
            assertEquals("06:04", it.getString(0))
            assertEquals("06:00", it.getString(1))
            assertTrue("new columns start empty", it.isNull(2))
            assertEquals("not finalized yet", 0, it.getInt(3))
        }
        db.query("SELECT weight, reps FROM exercise_set WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(185.0, it.getDouble(0), 0.0)
            assertEquals(8, it.getInt(1))
        }
        db.query("SELECT text, time, kcal_low, estimate_model FROM food_entry WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("burrito bowl", it.getString(0))
            assertEquals("12:30", it.getString(1))
            assertTrue("pending estimate", it.isNull(2))
            assertTrue(it.isNull(3))
        }
        db.query("SELECT alarm_time FROM routine_day WHERE weekday = 2").use {
            assertTrue(it.moveToFirst())
            assertEquals("06:00", it.getString(0))
        }
        db.close()
    }

    @Test fun v2ToV3KeepsFoodAndAddsMeals() {
        helper.createDatabase(dbName + "-3", 2).use { db ->
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-10-13', 0)")
            db.execSQL("INSERT INTO exercise (id, name) VALUES (1, 'Bench press')")
            db.execSQL(
                "INSERT INTO food_entry (id, date, time, text, logged_at, kcal_low, kcal_high, protein_g, carbs_g, fat_g, estimate_note, estimate_model, estimated_at) " +
                    "VALUES (1, '2026-10-13', '14:38', 'black bean burger', 1791900000000, 750, 1100, 30, 95, 40, 'note', 'claude-opus-5-5', 1791900001000)"
            )
        }
        val db = helper.runMigrationsAndValidate(dbName + "-3", 3, true)
        db.query("SELECT text, kcal_low, kcal_high, estimate_model, source, meal_id, servings FROM food_entry WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("black bean burger", it.getString(0))
            assertEquals(750, it.getInt(1))
            assertEquals(1100, it.getInt(2))
            assertEquals("claude-opus-5-5", it.getString(3))
            assertEquals("existing entries are AI entries", "ai", it.getString(4))
            assertTrue(it.isNull(5))
            assertEquals(1.0, it.getDouble(6), 0.0)
        }
        db.query("SELECT hidden FROM exercise WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM meal").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        db.close()
    }

    /** A phone that skipped a release goes straight from v1 to the newest schema. */
    @Test fun v1ToLatestInOneGo() {
        helper.createDatabase(dbName + "-chain", 1).use { db ->
            db.execSQL("INSERT INTO day (date, alarm_override, scheduled_alarm, scanned_at, morning_source, notes) VALUES ('2026-10-12', NULL, '06:00', '06:04', 'scan', NULL)")
            db.execSQL("INSERT INTO food_entry (id, date, time, text, logged_at, kcal_estimate, estimate_source) VALUES (1, '2026-10-12', '12:30', 'burrito bowl', 1791800000000, NULL, NULL)")
        }
        val db = helper.runMigrationsAndValidate(dbName + "-chain", 3, true)
        db.query("SELECT d.scanned_at, f.text, f.source, f.servings FROM day d JOIN food_entry f ON f.date = d.date").use {
            assertTrue(it.moveToFirst())
            assertEquals("06:04", it.getString(0))
            assertEquals("burrito bowl", it.getString(1))
            assertEquals("ai", it.getString(2))
            assertEquals(1.0, it.getDouble(3), 0.0)
        }
        db.close()
    }

    @Test fun v3ToV4AddsStepsAndKeepsDays() {
        helper.createDatabase(dbName + "-4", 3).use { db ->
            db.execSQL("INSERT INTO day (date, finalized, scanned_at, body_weight) VALUES ('2026-10-13', 1, '06:05', 176.0)")
        }
        val db = helper.runMigrationsAndValidate(dbName + "-4", 4, true)
        db.query("SELECT scanned_at, body_weight, finalized, steps FROM day WHERE date = '2026-10-13'").use {
            assertTrue(it.moveToFirst())
            assertEquals("06:05", it.getString(0))
            assertEquals(176.0, it.getDouble(1), 0.0)
            assertEquals(1, it.getInt(2))
            assertTrue("no steps yet", it.isNull(3))
        }
        db.close()
    }

    @Test fun v4ToV5AddsRatingsAndKeepsDays() {
        helper.createDatabase(dbName + "-5", 4).use { db ->
            db.execSQL("INSERT INTO day (date, finalized, scanned_at, steps) VALUES ('2026-10-14', 1, '06:02', 9120)")
        }
        val db = helper.runMigrationsAndValidate(dbName + "-5", 5, true)
        db.query("SELECT scanned_at, steps, finalized, mood, energy FROM day WHERE date = '2026-10-14'").use {
            assertTrue(it.moveToFirst())
            assertEquals("06:02", it.getString(0))
            assertEquals(9120L, it.getLong(1))
            assertEquals(1, it.getInt(2))
            assertTrue("no mood yet", it.isNull(3))
            assertTrue("no energy yet", it.isNull(4))
        }
        db.close()
    }

    @Test fun v5ToV6RenamesLegCurlKeepingItsHistory() {
        helper.createDatabase(dbName + "-6", 5).use { db ->
            db.execSQL("INSERT INTO exercise (id, name, hidden) VALUES (7, 'Leg curl', 0)")
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-10-14', 1)")
            db.execSQL("INSERT INTO day_workout (id, date, name) VALUES (1, '2026-10-14', 'Legs')")
            db.execSQL("INSERT INTO logged_exercise (id, workout_id, exercise_id, position) VALUES (1, 1, 7, 0)")
        }
        val db = helper.runMigrationsAndValidate(dbName + "-6", 6, true, AppDatabase.MIGRATION_5_6)
        db.query("SELECT e.name FROM logged_exercise l JOIN exercise e ON e.id = l.exercise_id").use {
            assertTrue(it.moveToFirst())
            assertEquals("Seated leg curl", it.getString(0))
        }
        db.close()
    }

    @Test fun v5ToV6LeavesLegCurlIfSeatedAlreadyExists() {
        helper.createDatabase(dbName + "-6b", 5).use { db ->
            db.execSQL("INSERT INTO exercise (id, name, hidden) VALUES (7, 'Leg curl', 0)")
            db.execSQL("INSERT INTO exercise (id, name, hidden) VALUES (8, 'seated leg curl', 0)")
        }
        val db = helper.runMigrationsAndValidate(dbName + "-6b", 6, true, AppDatabase.MIGRATION_5_6)
        db.query("SELECT name FROM exercise ORDER BY id").use {
            it.moveToFirst(); assertEquals("Leg curl", it.getString(0))
            it.moveToNext(); assertEquals("seated leg curl", it.getString(0))
        }
        db.close()
    }

    @Test fun v6ToV7AddsBarcodesAndKeepsMeals() {
        helper.createDatabase(dbName + "-7", 6).use { db ->
            db.execSQL("INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g, note) VALUES (1, 'Shake', 160, 30, 5, 3, '1 bottle')")
        }
        val db = helper.runMigrationsAndValidate(dbName + "-7", 7, true)
        db.query("SELECT name, kcal, note, barcode FROM meal WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("Shake", it.getString(0))
            assertEquals(160, it.getInt(1))
            assertEquals("1 bottle", it.getString(2))
            assertTrue("no barcode yet", it.isNull(3))
        }
        db.close()
    }
}
