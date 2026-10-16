package app.daybook.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.RenameColumn
import androidx.room.migration.AutoMigrationSpec
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
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.Migration1To2::class),
        // v3: saved meals, meal entries with servings, hideable exercises.
        AutoMigration(from = 2, to = 3),
        // v4: each day's step count.
        AutoMigration(from = 3, to = 4),
        // v5: mood and energy ratings.
        AutoMigration(from = 4, to = 5),
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "daybook.db")
                .addCallback(seedRoutine)
                .addCallback(seedCatalog)
                .build()
    }
}
