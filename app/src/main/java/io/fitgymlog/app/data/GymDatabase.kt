package io.fitgymlog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WorkoutEntity::class,
        WorkoutExerciseEntity::class,
        WorkoutSetEntity::class,
        RoutineEntity::class,
        RoutineExerciseEntity::class,
        CustomExerciseEntity::class,
        ExerciseLinkEntity::class,
    ],
    version = 2,
)
abstract class GymDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao

    abstract fun routines(): RoutineDao

    abstract fun customExercises(): CustomExerciseDao

    abstract fun exerciseLinks(): ExerciseLinkDao

    companion object {
        fun create(context: Context): GymDatabase =
            Room.databaseBuilder(context, GymDatabase::class.java, "fitgymlog.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Moves custom exercises' newline-separated links into exercise_link, where built-ins can have links too. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exercise_link` (`exerciseId` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`exerciseId`, `url`))",
                )
                val now = System.currentTimeMillis()
                db.query("SELECT id, links FROM custom_exercise").use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        cursor.getString(1).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { url ->
                            db.execSQL("INSERT OR IGNORE INTO exercise_link (exerciseId, url, addedAt) VALUES (?, ?, ?)", arrayOf(id, url, now))
                        }
                    }
                }
                db.execSQL("UPDATE custom_exercise SET links = ''")
            }
        }
    }
}
