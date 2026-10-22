package io.fitgymlog.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.fitgymlog.app.sync.TombstoneKind
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class WorkoutKey(val startedAt: Long, val title: String)

data class RowVersion(val id: Long, val uuid: String, val updatedAt: Long)

data class FinishedStamp(val count: Int, val updatedAtSum: Long)

@Dao
abstract class WorkoutDao {
    @Transaction
    @Query("SELECT * FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt DESC")
    abstract fun finished(): Flow<List<WorkoutWithExercises>>

    @Transaction
    @Query("SELECT * FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt DESC")
    abstract suspend fun allFinished(): List<WorkoutWithExercises>

    @Transaction
    @Query("SELECT * FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt DESC LIMIT :limit")
    abstract suspend fun recentFinished(limit: Int): List<WorkoutWithExercises>

    @Transaction
    @Query("SELECT * FROM workout WHERE id = :id")
    abstract fun observe(id: Long): Flow<WorkoutWithExercises?>

    @Transaction
    @Query(
        """
        SELECT * FROM workout
        WHERE endedAt IS NOT NULL AND id IN (SELECT workoutId FROM workout_exercise WHERE exerciseId = :exerciseId)
        ORDER BY startedAt DESC LIMIT :limit
        """,
    )
    abstract fun finishedWith(exerciseId: String, limit: Int): Flow<List<WorkoutWithExercises>>

    @Transaction
    @Query("SELECT * FROM workout WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    abstract suspend fun inProgress(): WorkoutWithExercises?

    @Query("SELECT startedAt, title FROM workout WHERE endedAt IS NOT NULL")
    abstract suspend fun finishedKeys(): List<WorkoutKey>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(updatedAt), 0) AS updatedAtSum FROM workout WHERE endedAt IS NOT NULL")
    abstract fun finishedStamp(): Flow<FinishedStamp>

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

    @Query("SELECT id, uuid, updatedAt FROM workout WHERE uuid = :uuid AND endedAt IS NOT NULL")
    abstract suspend fun finishedVersionOf(uuid: String): RowVersion?

    @Query("DELETE FROM workout WHERE uuid = :uuid AND endedAt IS NOT NULL AND updatedAt <= :deletedAt")
    abstract suspend fun deleteFinishedIfNotNewer(uuid: String, deletedAt: Long)

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

    @Query("SELECT * FROM routine WHERE id = :id")
    protected abstract suspend fun row(id: Long): RoutineEntity?

    @Query("SELECT * FROM routine WHERE uuid = :uuid")
    abstract suspend fun findByUuid(uuid: String): RoutineEntity?

    @Query("DELETE FROM routine WHERE uuid = :uuid AND updatedAt <= :deletedAt")
    abstract suspend fun deleteIfNotNewer(uuid: String, deletedAt: Long)

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

    @Transaction
    open suspend fun saveEdit(id: Long, name: String, exercises: List<RoutineExerciseEntity>, now: Long): Long {
        val routine = if (id == 0L) {
            RoutineEntity(uuid = UUID.randomUUID().toString(), updatedAt = now, name = name, position = nextPosition())
        } else {
            val existing = checkNotNull(row(id)) { "Routine $id no longer exists" }
            existing.copy(name = name, updatedAt = stampAfter(existing.updatedAt, now))
        }
        return save(routine, exercises)
    }
}

@Dao
abstract class CustomExerciseDao {
    @Query("SELECT * FROM custom_exercise ORDER BY name")
    abstract fun all(): Flow<List<CustomExerciseEntity>>

    @Query("SELECT * FROM custom_exercise ORDER BY name")
    abstract suspend fun allNow(): List<CustomExerciseEntity>

    @Query("SELECT updatedAt FROM custom_exercise WHERE id = :id")
    abstract suspend fun updatedAtOf(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(exercise: CustomExerciseEntity)

    @Transaction
    open suspend fun save(id: String, name: String, kind: String, now: Long) {
        upsert(CustomExerciseEntity(id, name, kind, stampAfter(updatedAtOf(id), now)))
    }
}

@Dao
abstract class ExerciseLinkDao {
    @Query("SELECT * FROM exercise_link ORDER BY addedAt")
    abstract fun all(): Flow<List<ExerciseLinkEntity>>

    @Query("SELECT * FROM exercise_link ORDER BY addedAt")
    abstract suspend fun allNow(): List<ExerciseLinkEntity>

    @Query("SELECT addedAt FROM exercise_link WHERE exerciseId = :exerciseId AND url = :url")
    abstract suspend fun addedAtOf(exerciseId: String, url: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(link: ExerciseLinkEntity)

    @Query("DELETE FROM exercise_link WHERE exerciseId = :exerciseId AND url = :url AND addedAt <= :deletedAt")
    abstract suspend fun deleteIfNotNewer(exerciseId: String, url: String, deletedAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(link: ExerciseLinkEntity)

    @Query("SELECT deletedAt FROM tombstone WHERE kind = :kind AND `key` = :key")
    protected abstract suspend fun deletedAtOf(kind: String, key: String): Long?

    @Transaction
    open suspend fun add(exerciseId: String, url: String, now: Long) {
        val removedAt = deletedAtOf(TombstoneKind.LINK, TombstoneKind.linkKey(exerciseId, url))
        insert(ExerciseLinkEntity(exerciseId, url, stampAfter(removedAt, now)))
    }
}

@Dao
abstract class TombstoneDao {
    @Query("SELECT * FROM tombstone")
    abstract fun all(): Flow<List<TombstoneEntity>>

    @Query("SELECT * FROM tombstone")
    abstract suspend fun allNow(): List<TombstoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun put(tombstones: List<TombstoneEntity>)

    @Delete
    abstract suspend fun delete(tombstones: List<TombstoneEntity>)

    @Query("SELECT id, uuid, updatedAt FROM workout WHERE id = :id")
    protected abstract suspend fun workoutVersion(id: Long): RowVersion?

    @Query("DELETE FROM workout WHERE id = :id")
    protected abstract suspend fun deleteWorkoutRow(id: Long)

    @Query("SELECT id, uuid, updatedAt FROM routine WHERE id = :id")
    protected abstract suspend fun routineVersion(id: Long): RowVersion?

    @Query("DELETE FROM routine WHERE id = :id")
    protected abstract suspend fun deleteRoutineRow(id: Long)

    @Query("SELECT addedAt FROM exercise_link WHERE exerciseId = :exerciseId AND url = :url")
    protected abstract suspend fun linkAddedAt(exerciseId: String, url: String): Long?

    @Query("DELETE FROM exercise_link WHERE exerciseId = :exerciseId AND url = :url")
    protected abstract suspend fun deleteLinkRow(exerciseId: String, url: String)

    @Transaction
    open suspend fun deleteWorkout(id: Long, now: Long) {
        workoutVersion(id)?.let { leaveTombstone(TombstoneKind.WORKOUT, it, now) }
        deleteWorkoutRow(id)
    }

    @Transaction
    open suspend fun deleteRoutine(id: Long, now: Long) {
        routineVersion(id)?.let { leaveTombstone(TombstoneKind.ROUTINE, it, now) }
        deleteRoutineRow(id)
    }

    @Transaction
    open suspend fun deleteLink(exerciseId: String, url: String, now: Long) {
        val deletedAt = stampAfter(linkAddedAt(exerciseId, url), now)
        put(listOf(TombstoneEntity(TombstoneKind.LINK, TombstoneKind.linkKey(exerciseId, url), deletedAt)))
        deleteLinkRow(exerciseId, url)
    }

    private suspend fun leaveTombstone(kind: String, row: RowVersion, now: Long) {
        if (row.uuid.isNotEmpty()) put(listOf(TombstoneEntity(kind, row.uuid, stampAfter(row.updatedAt, now))))
    }
}
