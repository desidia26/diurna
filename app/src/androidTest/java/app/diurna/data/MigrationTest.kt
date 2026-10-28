package app.diurna.data

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

    @Test fun v7ToV8AddsPhotoAndKeepsFood() {
        helper.createDatabase(dbName + "-8", 7).use { db ->
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-10-12', 0)")
            db.execSQL(
                "INSERT INTO food_entry (id, date, time, text, logged_at, kcal_low, kcal_high, source, servings) " +
                    "VALUES (1, '2026-10-12', '12:30', 'burrito', 1, 700, 900, 'ai', 1.0)"
            )
        }
        val db = helper.runMigrationsAndValidate(dbName + "-8", 8, true)
        db.query("SELECT text, kcal_low, kcal_high, photo FROM food_entry WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("burrito", it.getString(0))
            assertEquals(700, it.getInt(1))
            assertEquals(900, it.getInt(2))
            assertTrue("no photo yet", it.isNull(3))
        }
        db.close()
    }

    @Test fun v8ToV9AddsCardioAndKeepsStrengthSets() {
        helper.createDatabase(dbName + "-9", 8).use { db ->
            db.execSQL("INSERT INTO exercise (id, name, hidden) VALUES (3, 'Bench press', 0)")
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-10-20', 1)")
            db.execSQL("INSERT INTO day_workout (id, date, name) VALUES (1, '2026-10-20', 'Push A')")
            db.execSQL("INSERT INTO logged_exercise (id, workout_id, exercise_id, position) VALUES (1, 1, 3, 0)")
            db.execSQL("INSERT INTO exercise_set (id, logged_exercise_id, position, weight, reps) VALUES (1, 1, 0, 135.0, 8)")
        }
        val db = helper.runMigrationsAndValidate(dbName + "-9", 9, true)
        db.query("SELECT kind, cardio_style FROM exercise WHERE id = 3").use {
            assertTrue(it.moveToFirst())
            assertEquals("existing exercises are strength", "strength", it.getString(0))
            assertTrue("no cardio style", it.isNull(1))
        }
        db.query("SELECT weight, reps, distance_m, duration_s, split_s, best_split_s, watts, kcal, avg_hr FROM exercise_set WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(135.0, it.getDouble(0), 1e-9)
            assertEquals(8, it.getInt(1))
            for (col in 2..8) assertTrue("cardio column $col starts empty", it.isNull(col))
        }
        db.close()
    }

    @Test fun v9ToV10AddsDetailedNutritionColumns() {
        helper.createDatabase(dbName + "-10", 9).use { db ->
            db.execSQL(
                "INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g) VALUES (1, 'Oats', 150, 5, 27, 3)"
            )
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-10-28', 0)")
            db.execSQL(
                "INSERT INTO food_entry (id, date, time, text, logged_at, kcal_low, kcal_high, protein_g, carbs_g, fat_g, source, servings) " +
                    "VALUES (1, '2026-10-28', '08:00', 'Oats', 1, 150, 150, 5, 27, 3, 'meal', 1.0)"
            )
        }
        val db = helper.runMigrationsAndValidate(dbName + "-10", 10, true)
        db.query("SELECT fiber_g, sugars_g, sat_fat_g, omega3_g, cholesterol_mg FROM meal WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            for (i in 0..4) assertTrue("meal detail col $i starts null", it.isNull(i))
        }
        db.query("SELECT fiber_g, sugars_g, sat_fat_g, omega3_g, cholesterol_mg, protein_g, carbs_g FROM food_entry WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            for (i in 0..4) assertTrue("food detail col $i starts null", it.isNull(i))
            assertEquals(5, it.getInt(5))
            assertEquals(27, it.getInt(6))
        }
        db.close()
    }

    @Test fun v10ToV11AddsAminoColumnsAndFdcCache() {
        helper.createDatabase(dbName + "-11", 10).use { db ->
            db.execSQL(
                "INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g, fiber_g) VALUES (1, 'Tofu bowl', 400, 28, 30, 18, 6)"
            )
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-10-28', 0)")
            db.execSQL(
                "INSERT INTO food_entry (id, date, time, text, logged_at, kcal_low, kcal_high, protein_g, carbs_g, fat_g, fiber_g, source, servings) " +
                    "VALUES (1, '2026-10-28', '12:00', 'Tofu bowl', 1, 400, 400, 28, 30, 18, 6, 'meal', 1.0)"
            )
        }
        val db = helper.runMigrationsAndValidate(dbName + "-11", 11, true)
        db.query("SELECT his_mg, lys_mg, ingredients_json, amino_matched, amino_total, fiber_g FROM meal WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertTrue("his starts null", it.isNull(0))
            assertTrue("lys starts null", it.isNull(1))
            assertTrue("ingredients starts null", it.isNull(2))
            assertTrue("amino_matched starts null", it.isNull(3))
            assertTrue("amino_total starts null", it.isNull(4))
            assertEquals(6, it.getInt(5))
        }
        db.query("SELECT his_mg, trp_mg, ingredients_json, amino_matched, amino_total, protein_g FROM food_entry WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
            assertTrue(it.isNull(1))
            assertTrue(it.isNull(2))
            assertTrue(it.isNull(3))
            assertTrue(it.isNull(4))
            assertEquals(28, it.getInt(5))
        }
        db.query("SELECT COUNT(*) FROM fdc_food_cache").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }

    @Test fun v11ToV12AddsRecipeFieldsAndExistingMealsStaySimple() {
        helper.createDatabase(dbName + "-12", 11).use { db ->
            db.execSQL(
                "INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g, fiber_g, lys_mg) " +
                    "VALUES (1, 'Oats', 150, 5, 27, 3, 4, 200)"
            )
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-10-28', 0)")
            db.execSQL(
                "INSERT INTO food_entry (id, date, time, text, logged_at, kcal_low, kcal_high, protein_g, carbs_g, fat_g, source, meal_id, servings) " +
                    "VALUES (1, '2026-10-28', '08:00', 'Oats', 1, 150, 150, 5, 27, 3, 'meal', 1, 1.0)"
            )
        }
        val db = helper.runMigrationsAndValidate(dbName + "-12", 12, true)
        db.query("SELECT kind, servings_yield, cooked_weight_g, kcal, protein_g, lys_mg FROM meal WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("existing meals become simple", "simple", it.getString(0))
            assertTrue("servings_yield starts null", it.isNull(1))
            assertTrue("cooked_weight starts null", it.isNull(2))
            assertEquals(150, it.getInt(3))
            assertEquals(5, it.getInt(4))
            assertEquals(200, it.getInt(5))
        }
        db.query("SELECT kcal_low, protein_g, carbs_g, fat_g, servings, meal_id FROM food_entry WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("logged macros unchanged", 150, it.getInt(0))
            assertEquals(5, it.getInt(1))
            assertEquals(27, it.getInt(2))
            assertEquals(3, it.getInt(3))
            assertEquals(1.0, it.getDouble(4), 0.0)
            assertEquals(1L, it.getLong(5))
        }
        db.query("SELECT COUNT(*) FROM recipe_ingredient").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }
    @Test fun v12ToV13AddsRestaurantFieldsAndExistingMealsStay() {
        helper.createDatabase(dbName + "-13", 12).use { db ->
            db.execSQL(
                "INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g, kind, barcode) " +
                    "VALUES (1, 'Shake', 160, 30, 5, 3, 'simple', '0123456789012')"
            )
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-10-28', 0)")
            db.execSQL(
                "INSERT INTO food_entry (id, date, time, text, logged_at, kcal_low, kcal_high, protein_g, carbs_g, fat_g, source, meal_id, servings) " +
                    "VALUES (1, '2026-10-28', '08:00', 'Shake', 1, 160, 160, 30, 5, 3, 'meal', 1, 1.0)"
            )
        }
        val db = helper.runMigrationsAndValidate(dbName + "-13", 13, true)
        db.query("SELECT kind, restaurant_name, nutrition_source, barcode, kcal, protein_g FROM meal WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("simple meals stay simple", "simple", it.getString(0))
            assertTrue("restaurant_name starts null", it.isNull(1))
            assertTrue("nutrition_source starts null", it.isNull(2))
            assertEquals("0123456789012", it.getString(3))
            assertEquals(160, it.getInt(4))
            assertEquals(30, it.getInt(5))
        }
        db.query("SELECT kcal_low, protein_g, carbs_g, fat_g, meal_id FROM food_entry WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("logged macros unchanged", 160, it.getInt(0))
            assertEquals(30, it.getInt(1))
            assertEquals(5, it.getInt(2))
            assertEquals(3, it.getInt(3))
            assertEquals(1L, it.getLong(4))
        }
        db.close()
    }


    @Test fun v13ToV14AddsBarcodeSnapshotColumnsAndExistingRowsGetNull() {
        helper.createDatabase(dbName + "-14", 13).use { db ->
            db.execSQL(
                "INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g, kind, barcode, restaurant_name, nutrition_source) " +
                    "VALUES (1, 'Shake', 160, 30, 5, 3, 'simple', '0123456789012', NULL, NULL)"
            )
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-10-28', 0)")
            db.execSQL(
                "INSERT INTO food_entry (id, date, time, text, logged_at, kcal_low, kcal_high, protein_g, carbs_g, fat_g, source, meal_id, servings) " +
                    "VALUES (1, '2026-10-28', '08:00', 'Shake', 1, 160, 160, 30, 5, 3, 'meal', 1, 1.0)"
            )
        }
        val db = helper.runMigrationsAndValidate(dbName + "-14", 14, true)
        db.query("SELECT barcode, off_snapshot_json, kcal, protein_g FROM meal WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("0123456789012", it.getString(0))
            assertTrue("off_snapshot starts null", it.isNull(1))
            assertEquals(160, it.getInt(2))
            assertEquals(30, it.getInt(3))
        }
        db.query("SELECT barcode, off_snapshot_json, kcal_low, protein_g, carbs_g, fat_g FROM food_entry WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertTrue("existing food_entry barcode null", it.isNull(0))
            assertTrue("existing food_entry snapshot null", it.isNull(1))
            assertEquals("logged macros unchanged", 160, it.getInt(2))
            assertEquals(30, it.getInt(3))
            assertEquals(5, it.getInt(4))
            assertEquals(3, it.getInt(5))
        }
        db.close()
    }



    @Test fun v14ToV15CopiesPrimaryPhotoIntoGalleryAndLeavesNulls() {
        helper.createDatabase(dbName + "-15", 14).use { db ->
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-10-28', 0)")
            db.execSQL(
                "INSERT INTO food_entry (id, date, time, text, logged_at, kcal_low, kcal_high, protein_g, carbs_g, fat_g, source, servings, photo) " +
                    "VALUES (1, '2026-10-28', '12:00', 'burrito', 1, 700, 900, 30, 80, 20, 'ai', 1.0, 'burrito.jpg')"
            )
            db.execSQL(
                "INSERT INTO food_entry (id, date, time, text, logged_at, source, servings, photo) " +
                    "VALUES (2, '2026-10-28', '13:00', 'no photo', 2, 'ai', 1.0, NULL)"
            )
            db.execSQL(
                "INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g, kind) " +
                    "VALUES (1, 'Shake', 160, 30, 5, 3, 'simple')"
            )
        }
        val db = helper.runMigrationsAndValidate(dbName + "-15", 15, true, AppDatabase.MIGRATION_14_15)
        db.query("SELECT photo, kcal_low, protein_g FROM food_entry WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("primary photo column kept", "burrito.jpg", it.getString(0))
            assertEquals("macros unchanged", 700, it.getInt(1))
            assertEquals(30, it.getInt(2))
        }
        db.query("SELECT owner_type, owner_id, file_name, position FROM food_photo ORDER BY owner_id").use {
            assertTrue(it.moveToFirst())
            assertEquals("entry", it.getString(0))
            assertEquals(1L, it.getLong(1))
            assertEquals("burrito.jpg", it.getString(2))
            assertEquals(0, it.getInt(3))
            assertTrue("entries without photo get no gallery row", !it.moveToNext())
        }
        db.query("SELECT COUNT(*) FROM food_photo WHERE owner_type = 'meal'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }
}
