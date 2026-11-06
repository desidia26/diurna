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


    @Test fun v15ToV16AddsIngredientDensityCache() {
        helper.createDatabase(dbName + "-16", 15).use { db ->
            db.execSQL("INSERT INTO day (date, finalized) VALUES ('2026-11-04', 0)")
            db.execSQL(
                "INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g, kind) " +
                    "VALUES (1, 'Soup', 200, 10, 20, 5, 'recipe')"
            )
        }
        // AutoMigration 15→16 creates ingredient_density_cache; schemas validate columns.
        val db = helper.runMigrationsAndValidate(dbName + "-16", 16, true)
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ingredient_density_cache'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("ingredient_density_cache", it.getString(0))
        }
        db.query("SELECT COUNT(*) FROM meal").use {
            assertTrue(it.moveToFirst())
            assertEquals("existing meals kept", 1, it.getInt(0))
        }
        db.execSQL(
            "INSERT INTO ingredient_density_cache " +
                "(query_key, normalized_name, unit_kind, g_per_ml, grams_per_each, source, fdc_id, basis, description, cached_at, retry_after) " +
                "VALUES ('flour|volume', 'flour', 'volume', 0.53, NULL, 'builtin', NULL, 'AP flour', NULL, 1, NULL)"
        )
        db.query("SELECT g_per_ml, source FROM ingredient_density_cache WHERE query_key='flour|volume'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0.53, it.getDouble(0), 1e-9)
            assertEquals("builtin", it.getString(1))
        }
        db.close()
    }

    @Test fun v16ToV17AddsExerciseNoteAndExistingExercisesHaveNone() {
        helper.createDatabase(dbName + "-17", 16).use { db ->
            db.execSQL("INSERT INTO exercise (id, name, hidden, kind, cardio_style) VALUES (1, 'Bench press', 0, 'strength', NULL)")
            db.execSQL("INSERT INTO exercise (id, name, hidden, kind, cardio_style) VALUES (2, 'Row', 1, 'cardio', '500m')")
        }
        val db = helper.runMigrationsAndValidate(dbName + "-17", 17, true)
        db.query("SELECT name, hidden, kind, cardio_style, note FROM exercise ORDER BY id").use {
            assertTrue(it.moveToFirst())
            assertEquals("Bench press", it.getString(0))
            assertTrue("existing exercises start without a note", it.isNull(4))
            assertTrue(it.moveToNext())
            assertEquals(1, it.getInt(1))
            assertEquals("cardio", it.getString(2))
            assertEquals("500m", it.getString(3))
            assertTrue(it.isNull(4))
        }
        db.close()
    }

    /** Main's shipped v16 → v18: exercise.note (#45) plus g_per_ml + nutrition cache (#48). */
    @Test fun v16ToV18AddsExerciseNoteGPerMlAndNutritionCache() {
        helper.createDatabase(dbName + "-16to18", 16).use { db ->
            db.execSQL("INSERT INTO exercise (id, name, hidden, kind, cardio_style) VALUES (1, 'Squat', 0, 'strength', NULL)")
            db.execSQL(
                "INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g, kind) " +
                    "VALUES (1, 'Soup', 200, 10, 20, 5, 'recipe')"
            )
            db.execSQL(
                "INSERT INTO recipe_ingredient " +
                    "(id, meal_id, position, name, amount, unit, grams, density_id, grams_per_each, " +
                    "protein_g, carbs_g, fat_g, kcal) " +
                    "VALUES (1, 1, 0, 'broth', 1.0, 'cup', 240.0, 'usda', NULL, 0, 0, 0, 0)"
            )
        }
        val db = helper.runMigrationsAndValidate(
            dbName + "-16to18", 18, true, AppDatabase.MIGRATION_17_18,
        )
        db.query("SELECT note FROM exercise WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertTrue("exercise.note starts null", it.isNull(0))
        }
        db.query("SELECT name, grams, g_per_ml, density_id FROM recipe_ingredient WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("broth", it.getString(0))
            assertEquals(240.0, it.getDouble(1), 0.0)
            assertTrue("g_per_ml starts null for pre-v18 rows", it.isNull(2))
            assertEquals("usda", it.getString(3))
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ingredient_nutrition_cache'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("ingredient_nutrition_cache", it.getString(0))
        }
        db.close()
    }

    /** #45's v17 (exercise.note, no g_per_ml / nutrition cache) → v18. */
    @Test fun v17FromExerciseNotesToV18AddsGPerMlAndNutritionCache() {
        helper.createDatabase(dbName + "-45to18", 17).use { db ->
            db.execSQL(
                "INSERT INTO exercise (id, name, hidden, kind, cardio_style, note) " +
                    "VALUES (1, 'Bench press', 0, 'strength', NULL, 'elbows in')"
            )
            db.execSQL(
                "INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g, kind) " +
                    "VALUES (1, 'Soup', 200, 10, 20, 5, 'recipe')"
            )
            db.execSQL(
                "INSERT INTO recipe_ingredient " +
                    "(id, meal_id, position, name, amount, unit, grams, density_id, grams_per_each, " +
                    "protein_g, carbs_g, fat_g, kcal) " +
                    "VALUES (1, 1, 0, 'broth', 1.0, 'cup', 240.0, 'usda', NULL, 0, 0, 0, 0)"
            )
        }
        val db = helper.runMigrationsAndValidate(
            dbName + "-45to18", 18, true, AppDatabase.MIGRATION_17_18,
        )
        db.query("SELECT note FROM exercise WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("elbows in", it.getString(0))
        }
        db.query("SELECT g_per_ml FROM recipe_ingredient WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='ingredient_nutrition_cache'"
        ).use {
            assertTrue(it.moveToFirst())
        }
        db.close()
    }

    /**
     * Larry's build 142 shipped a divergent v17: g_per_ml + ingredient_nutrition_cache, no
     * exercise.note. Shape a v16 DB into that preview schema, then migrate 17→18 and confirm
     * note is added without dropping the preview columns/table.
     */
    @Test fun v17FromBuild142ToV18AddsExerciseNoteIdempotently() {
        helper.createDatabase(dbName + "-142to18", 16).use { db ->
            db.execSQL("INSERT INTO exercise (id, name, hidden, kind, cardio_style) VALUES (1, 'Row', 0, 'cardio', '500m')")
            db.execSQL(
                "INSERT INTO meal (id, name, kcal, protein_g, carbs_g, fat_g, kind) " +
                    "VALUES (1, 'Soup', 200, 10, 20, 5, 'recipe')"
            )
            db.execSQL(
                "INSERT INTO recipe_ingredient " +
                    "(id, meal_id, position, name, amount, unit, grams, density_id, grams_per_each, " +
                    "protein_g, carbs_g, fat_g, kcal) " +
                    "VALUES (1, 1, 0, 'broth', 1.0, 'cup', 240.0, 'usda', NULL, 0, 0, 0, 0)"
            )
            // Reshape to build 142's v17 (b8b7cc5): g_per_ml + nutrition cache, no exercise.note.
            db.execSQL("ALTER TABLE `recipe_ingredient` ADD COLUMN `g_per_ml` REAL")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `ingredient_nutrition_cache` (" +
                    "`normalized_name` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`fdc_id` INTEGER, " +
                    "`kcal_per_100` INTEGER NOT NULL, " +
                    "`protein_g_per_100` INTEGER NOT NULL, " +
                    "`carbs_g_per_100` INTEGER NOT NULL, " +
                    "`fat_g_per_100` INTEGER NOT NULL, " +
                    "`fiber_g_per_100` INTEGER, " +
                    "`sugars_g_per_100` INTEGER, " +
                    "`basis` TEXT, " +
                    "`description` TEXT, " +
                    "`cached_at` INTEGER NOT NULL, " +
                    "`retry_after` INTEGER, " +
                    "PRIMARY KEY(`normalized_name`))"
            )
            db.execSQL(
                "UPDATE recipe_ingredient SET g_per_ml = 1.0 WHERE id = 1"
            )
            db.execSQL(
                "INSERT INTO ingredient_nutrition_cache " +
                    "(normalized_name, source, fdc_id, kcal_per_100, protein_g_per_100, " +
                    "carbs_g_per_100, fat_g_per_100, fiber_g_per_100, sugars_g_per_100, " +
                    "basis, description, cached_at, retry_after) " +
                    "VALUES ('broth', 'usda', 9, 10, 1, 1, 0, NULL, NULL, 'test', NULL, 1, NULL)"
            )
            // Match build 142's room identity hash so this looks like that install.
            db.execSQL("DELETE FROM room_master_table")
            db.execSQL(
                "INSERT INTO room_master_table (id, identity_hash) " +
                    "VALUES (42, '8153b4a0da2db14d5a2a9b8be172c1a8')"
            )
            db.version = 17
        }
        val db = helper.runMigrationsAndValidate(
            dbName + "-142to18", 18, true, AppDatabase.MIGRATION_17_18,
        )
        db.query("SELECT note FROM exercise WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertTrue("build-142 exercises gain a null note", it.isNull(0))
        }
        db.query("SELECT g_per_ml FROM recipe_ingredient WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(1.0, it.getDouble(0), 0.0)
        }
        db.query(
            "SELECT kcal_per_100 FROM ingredient_nutrition_cache WHERE normalized_name='broth'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(10, it.getInt(0))
        }
        db.close()
    }
}
