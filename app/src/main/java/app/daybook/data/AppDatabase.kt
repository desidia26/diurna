package app.daybook.data

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
    ],
    version = 6,
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
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayDao(): DayDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun routineDao(): RoutineDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun foodDao(): FoodDao
    abstract fun mealDao(): MealDao

    /** v2: food estimates, body weight, finalized-day snapshots. The v1 estimate columns were never written. */
    @DeleteColumn(tableName = "food_entry", columnName = "kcal_estimate")
    @RenameColumn(tableName = "food_entry", fromColumnName = "estimate_source", toColumnName = "estimate_model")
    class Migration1To2 : AutoMigrationSpec

    companion object {
        const val DEFAULT_ALARM = "06:30"

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
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE exercise SET name = 'Seated leg curl' WHERE name = 'Leg curl' COLLATE NOCASE " +
                        "AND NOT EXISTS (SELECT 1 FROM exercise WHERE name = 'Seated leg curl' COLLATE NOCASE)"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "daybook.db")
                .addMigrations(MIGRATION_5_6)
                .addCallback(seedRoutine)
                .addCallback(seedCatalog)
                .build()
    }
}
