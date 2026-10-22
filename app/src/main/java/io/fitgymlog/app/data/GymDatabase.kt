package io.fitgymlog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WorkoutEntity::class,
        WorkoutExerciseEntity::class,
        WorkoutSetEntity::class,
        RoutineEntity::class,
        RoutineExerciseEntity::class,
        CustomExerciseEntity::class,
        ExerciseLinkEntity::class,
        TombstoneEntity::class,
    ],
    version = 1,
)
abstract class GymDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao

    abstract fun routines(): RoutineDao

    abstract fun customExercises(): CustomExerciseDao

    abstract fun exerciseLinks(): ExerciseLinkDao

    abstract fun tombstones(): TombstoneDao

    companion object {
        fun create(context: Context): GymDatabase =
            Room.databaseBuilder(context, GymDatabase::class.java, "fitgymlog.db").build()
    }
}
