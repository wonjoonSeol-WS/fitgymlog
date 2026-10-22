package io.fitgymlog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class WorkoutKey(val startedAt: Long, val title: String)

@Dao
abstract class WorkoutDao {
    @Transaction
    @Query("SELECT * FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt DESC")
    abstract fun finished(): Flow<List<WorkoutWithExercises>>

    @Transaction
    @Query("SELECT * FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt DESC")
    abstract suspend fun allFinished(): List<WorkoutWithExercises>

    @Transaction
    @Query("SELECT * FROM workout WHERE id = :id")
    abstract fun observe(id: Long): Flow<WorkoutWithExercises?>

    @Transaction
    @Query("SELECT * FROM workout WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun inProgress(): WorkoutWithExercises?

    @Query("SELECT startedAt, title FROM workout WHERE endedAt IS NOT NULL")
    abstract suspend fun finishedKeys(): List<WorkoutKey>

    @Query(
        """
        SELECT s.* FROM workout_set s
        WHERE s.workoutExerciseId = (
            SELECT e.id FROM workout_exercise e JOIN workout w ON e.workoutId = w.id
            WHERE e.exerciseId = :exerciseId AND w.endedAt IS NOT NULL
            ORDER BY w.startedAt DESC, e.position DESC LIMIT 1
        )
        ORDER BY s.position
        """,
    )
    abstract suspend fun lastSetsOf(exerciseId: String): List<WorkoutSetEntity>

    @Query("SELECT DISTINCT exerciseId FROM workout_exercise")
    abstract suspend fun usedExerciseIds(): List<String>

    @Insert
    protected abstract suspend fun insertWorkout(workout: WorkoutEntity): Long

    @Update
    protected abstract suspend fun updateWorkout(workout: WorkoutEntity)

    @Insert
    protected abstract suspend fun insertExercise(exercise: WorkoutExerciseEntity): Long

    @Insert
    protected abstract suspend fun insertSets(sets: List<WorkoutSetEntity>)

    @Query("DELETE FROM workout_exercise WHERE workoutId = :workoutId")
    protected abstract suspend fun deleteExercisesOf(workoutId: Long)

    @Query("DELETE FROM workout WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("UPDATE workout_exercise SET exerciseId = :to WHERE exerciseId = :from")
    abstract suspend fun replaceExerciseId(from: String, to: String)

    /** Writes the whole workout, replacing its exercises and sets. Returns the workout id. */
    @Transaction
    open suspend fun save(graph: WorkoutGraph): Long {
        val id = if (graph.workout.id == 0L) {
            insertWorkout(graph.workout)
        } else {
            updateWorkout(graph.workout)
            deleteExercisesOf(graph.workout.id)
            graph.workout.id
        }
        graph.exercises.forEach { (exercise, sets) ->
            val exerciseId = insertExercise(exercise.copy(id = 0, workoutId = id))
            insertSets(sets.map { it.copy(id = 0, workoutExerciseId = exerciseId) })
        }
        return id
    }

    @Transaction
    open suspend fun saveAll(graphs: List<WorkoutGraph>) {
        graphs.forEach { save(it) }
    }
}

data class WorkoutGraph(
    val workout: WorkoutEntity,
    val exercises: List<Pair<WorkoutExerciseEntity, List<WorkoutSetEntity>>>,
)

@Dao
abstract class RoutineDao {
    @Transaction
    @Query("SELECT * FROM routine ORDER BY position, id")
    abstract fun all(): Flow<List<RoutineWithExercises>>

    @Transaction
    @Query("SELECT * FROM routine ORDER BY position, id")
    abstract suspend fun allNow(): List<RoutineWithExercises>

    @Transaction
    @Query("SELECT * FROM routine WHERE id = :id")
    abstract suspend fun find(id: Long): RoutineWithExercises?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM routine")
    abstract suspend fun nextPosition(): Int

    @Insert
    protected abstract suspend fun insertRoutine(routine: RoutineEntity): Long

    @Update
    protected abstract suspend fun updateRoutine(routine: RoutineEntity)

    @Insert
    protected abstract suspend fun insertExercises(exercises: List<RoutineExerciseEntity>)

    @Query("DELETE FROM routine_exercise WHERE routineId = :routineId")
    protected abstract suspend fun deleteExercisesOf(routineId: Long)

    @Query("DELETE FROM routine WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("UPDATE routine_exercise SET exerciseId = :to WHERE exerciseId = :from")
    abstract suspend fun replaceExerciseId(from: String, to: String)

    @Transaction
    open suspend fun save(routine: RoutineEntity, exercises: List<RoutineExerciseEntity>): Long {
        val id = if (routine.id == 0L) {
            insertRoutine(routine)
        } else {
            updateRoutine(routine)
            deleteExercisesOf(routine.id)
            routine.id
        }
        insertExercises(exercises.mapIndexed { index, exercise -> exercise.copy(id = 0, routineId = id, position = index) })
        return id
    }
}

@Dao
interface CustomExerciseDao {
    @Query("SELECT * FROM custom_exercise ORDER BY name")
    fun all(): Flow<List<CustomExerciseEntity>>

    @Query("SELECT * FROM custom_exercise ORDER BY name")
    suspend fun allNow(): List<CustomExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exercise: CustomExerciseEntity)

    @Query("DELETE FROM custom_exercise WHERE id = :id")
    suspend fun delete(id: String)
}
