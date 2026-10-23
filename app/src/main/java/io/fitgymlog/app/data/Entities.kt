package io.fitgymlog.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "workout", indices = [Index("startedAt"), Index("endedAt"), Index("uuid")])
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Identity across devices for sync; the row id differs per phone. */
    val uuid: String,
    val updatedAt: Long,
    val title: String,
    val startedAt: Long,
    val endedAt: Long?,
    val notes: String,
    val routineId: Long?,
    val source: String,
    val focus: Int = 0,
    val shownExerciseIndex: Int? = null,
    val shownSetIndex: Int? = null,
    val restEndsAt: Long? = null,
    val lastActivityAt: Long,
)

@Entity(
    tableName = "workout_exercise",
    foreignKeys = [ForeignKey(WorkoutEntity::class, ["id"], ["workoutId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workoutId"), Index("exerciseId")],
)
data class WorkoutExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val position: Int,
    val key: Long,
    val exerciseId: String,
    val restSeconds: Int,
    val notes: String,
)

@Entity(
    tableName = "workout_set",
    foreignKeys = [ForeignKey(WorkoutExerciseEntity::class, ["id"], ["workoutExerciseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("workoutExerciseId")],
)
data class WorkoutSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutExerciseId: Long,
    val position: Int,
    val type: String,
    val weightKg: Double?,
    val reps: Int?,
    val targetReps: Int?,
    val rpe: Double?,
    val distanceMeters: Double?,
    val durationSeconds: Int?,
    val completedAt: Long?,
    val level: Int? = null,
    val speedKmh: Double? = null,
)

@Entity(tableName = "routine", indices = [Index("uuid")])
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val updatedAt: Long,
    val name: String,
    val position: Int,
)

@Entity(
    tableName = "routine_exercise",
    foreignKeys = [ForeignKey(RoutineEntity::class, ["id"], ["routineId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("routineId")],
)
data class RoutineExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val position: Int,
    val exerciseId: String,
    val sets: Int,
    val reps: Int?,
    val weightKg: Double?,
    val restSeconds: Int,
    val durationSeconds: Int? = null,
    val level: Int? = null,
    val speedKmh: Double? = null,
)

@Entity(tableName = "custom_exercise")
data class CustomExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val updatedAt: Long,
)

/** Links the user added to any exercise, built-in or custom: a form video, an article, a coach's clip. */
@Entity(tableName = "exercise_link", primaryKeys = ["exerciseId", "url"])
data class ExerciseLinkEntity(
    val exerciseId: String,
    val url: String,
    val addedAt: Long,
)

/** Records a deletion so sync can remove the same item on other devices instead of bringing it back. */
@Entity(tableName = "tombstone", primaryKeys = ["kind", "key"])
data class TombstoneEntity(
    val kind: String,
    val key: String,
    val deletedAt: Long,
)

data class ExerciseWithSets(
    @Embedded val exercise: WorkoutExerciseEntity,
    @Relation(parentColumn = "id", entityColumn = "workoutExerciseId") val sets: List<WorkoutSetEntity>,
)

data class WorkoutWithExercises(
    @Embedded val workout: WorkoutEntity,
    @Relation(entity = WorkoutExerciseEntity::class, parentColumn = "id", entityColumn = "workoutId")
    val exercises: List<ExerciseWithSets>,
)

data class RoutineWithExercises(
    @Embedded val routine: RoutineEntity,
    @Relation(parentColumn = "id", entityColumn = "routineId") val exercises: List<RoutineExerciseEntity>,
)
