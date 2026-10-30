package app.diurna.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RenameColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ExerciseEntity::class,
        WorkoutTemplateEntity::class,
        TemplateExerciseEntity::class,
        RoutineDayEntity::class,
        DayEntity::class,
        DayWorkoutEntity::class,
        LoggedExerciseEntity::class,
        ExerciseSetEntity::class,
        FoodEntryEntity::class,
        MealEntity::class,
        RecipeIngredientEntity::class,
        FoodPhotoEntity::class,
        FdcFoodCacheEntity::class,
    ],
    version = 15,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.Migration1To2::class),
        // v3: saved meals, meal entries with servings, hideable exercises.
        AutoMigration(from = 2, to = 3),
        // v4: each day's step count.
        AutoMigration(from = 3, to = 4),
        // v5: mood and energy ratings.
        AutoMigration(from = 4, to = 5),
        // v6 is data only: see MIGRATION_5_6.
        // v7: barcodes on saved meals.
        AutoMigration(from = 6, to = 7),
        // v8: an optional photo on food entries.
        AutoMigration(from = 7, to = 8),
        // v9: cardio exercises (exercise.kind) and their pieces' distance, time, split, power, energy, HR.
        AutoMigration(from = 8, to = 9),
        // v10: detailed nutrition on meals and food entries (fiber, sugars, starch, lipid breakdown).
        AutoMigration(from = 9, to = 10),
        // v11: amino acids (mg) on meals/food entries + FDC ingredient cache.
        AutoMigration(from = 10, to = 11),
        // v12: meal.kind + servings_yield + cooked_weight_g; recipe_ingredient snapshots.
        AutoMigration(from = 11, to = 12),
        // v13: restaurant_name + nutrition_source on meal.
        AutoMigration(from = 12, to = 13),
        // v14: barcode + OFF snapshot on food_entry; OFF snapshot on meal.
        AutoMigration(from = 13, to = 14),
        // v15: food_photo gallery (data copy of food_entry.photo is in MIGRATION_14_15).
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayDao(): DayDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun routineDao(): RoutineDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun foodDao(): FoodDao
    abstract fun mealDao(): MealDao
    abstract fun recipeIngredientDao(): RecipeIngredientDao
    abstract fun foodPhotoDao(): FoodPhotoDao
    abstract fun fdcFoodCacheDao(): FdcFoodCacheDao

    /** v2: food estimates, body weight, finalized-day snapshots. The v1 estimate columns were never written. */
    @DeleteColumn(tableName = "food_entry", columnName = "kcal_estimate")
    @RenameColumn(tableName = "food_entry", fromColumnName = "estimate_source", toColumnName = "estimate_model")
    class Migration1To2 : AutoMigrationSpec

    companion object {
        const val DEFAULT_ALARM = "06:30"
        /** Kept from the app's first name, like the app id; renaming it would orphan everyone's data. */
        const val NAME = "daybook.db"

        /** Every weekday exists from the start, at the default alarm with no workout. */
        val seedRoutine = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                for (weekday in 1..7) {
                    db.execSQL(
                        "INSERT INTO routine_day (weekday, alarm_time, template_id) VALUES (?, ?, NULL)",
                        arrayOf<Any>(weekday, DEFAULT_ALARM),
                    )
                }
            }
        }

        /** Runs on every open, so existing installs pick up new seed exercises too. */
        val seedCatalog = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) = seedExercises(db)
        }

        /**
         * v6: "Leg curl" becomes "Seated leg curl", now that "Lying leg curl" is in the catalog too.
         * A rename keeps the id, so logged sessions and routines follow. Skipped if a
         * "Seated leg curl" already exists, so two exercises never end up with one name.
         */

        /**
         * v15: multi-photo gallery. Copies existing food_entry.photo rows into food_photo
         * (owner_type=entry, position=0). food_entry.photo stays as the primary photo.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `food_photo` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`owner_type` TEXT NOT NULL, " +
                        "`owner_id` INTEGER NOT NULL, " +
                        "`file_name` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_food_photo_owner_type_owner_id` " +
                        "ON `food_photo` (`owner_type`, `owner_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_food_photo_file_name` ON `food_photo` (`file_name`)"
                )
                db.execSQL(
                    "INSERT INTO food_photo (owner_type, owner_id, file_name, position) " +
                        "SELECT 'entry', id, photo, 0 FROM food_entry " +
                        "WHERE photo IS NOT NULL AND length(photo) > 0"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE exercise SET name = 'Seated leg curl' WHERE name = 'Leg curl' COLLATE NOCASE " +
                        "AND NOT EXISTS (SELECT 1 FROM exercise WHERE name = 'Seated leg curl' COLLATE NOCASE)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_5_6, MIGRATION_14_15)
                .addCallback(seedRoutine)
                .addCallback(seedCatalog)
                .build()
    }
}
