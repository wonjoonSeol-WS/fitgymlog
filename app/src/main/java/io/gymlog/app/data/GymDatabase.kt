package io.gymlog.app.data

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
    ],
    version = 1,
)
abstract class GymDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao

    abstract fun routines(): RoutineDao

    abstract fun customExercises(): CustomExerciseDao

    companion object {
        fun create(context: Context): GymDatabase =
            Room.databaseBuilder(context, GymDatabase::class.java, "gymlog.db").build()
    }
}
