package io.fitgymlog.app.transfer

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.WorkoutSource
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything the app stores, in one versioned JSON file, for moving phones or keeping a copy. */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: String,
    val customExercises: List<BackupCustomExercise>,
    val routines: List<BackupRoutine>,
    val workouts: List<BackupWorkout>,
) {
    companion object {
        const val FORMAT = "fitgymlog-backup"
        const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun looksLike(text: String): Boolean = text.trimStart().startsWith("{") && FORMAT in text.take(200)

        fun decode(text: String): BackupFile = json.decodeFromString(serializer(), text).also {
            require(it.format == FORMAT) { "not a FitGymLog backup" }
            require(it.version <= VERSION) { "backup version ${it.version} is newer than this app" }
        }

        fun encode(backup: BackupFile): String = json.encodeToString(serializer(), backup)
    }
}

@Serializable
data class BackupCustomExercise(val id: String, val name: String, val kind: String, val links: List<String>)

@Serializable
data class BackupRoutine(val name: String, val exercises: List<BackupRoutineExercise>)

@Serializable
data class BackupRoutineExercise(val exerciseId: String, val sets: Int, val reps: Int?, val weightKg: Double?, val restSeconds: Int)

@Serializable
data class BackupWorkout(
    val title: String,
    val startedAt: String,
    val endedAt: String?,
    val notes: String,
    val source: String,
    val exercises: List<BackupWorkoutExercise>,
)

@Serializable
data class BackupWorkoutExercise(val exerciseId: String, val restSeconds: Int, val notes: String, val sets: List<BackupSet>)

@Serializable
data class BackupSet(
    val type: String,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val distanceMeters: Double? = null,
    val durationSeconds: Int? = null,
    val completedAt: String? = null,
)

fun Workout.toBackup() = BackupWorkout(
    title = title,
    startedAt = startedAt.toString(),
    endedAt = endedAt?.toString(),
    notes = notes,
    source = source.name,
    exercises = exercises.map { exercise ->
        BackupWorkoutExercise(
            exerciseId = exercise.exerciseId,
            restSeconds = exercise.restSeconds,
            notes = exercise.notes,
            sets = exercise.sets.map { set ->
                BackupSet(set.type.name, set.weightKg, set.reps, set.rpe, set.distanceMeters, set.durationSeconds, set.completedAt?.toString())
            },
        )
    },
)

fun BackupWorkout.toWorkout() = Workout(
    title = title,
    startedAt = Instant.parse(startedAt),
    endedAt = endedAt?.let(Instant::parse),
    notes = notes,
    source = WorkoutSource.entries.firstOrNull { it.name == source } ?: WorkoutSource.BACKUP,
    exercises = exercises.mapIndexed { index, exercise ->
        WorkoutExercise(
            key = index + 1L,
            exerciseId = exercise.exerciseId,
            restSeconds = exercise.restSeconds,
            notes = exercise.notes,
            sets = exercise.sets.map { set ->
                WorkoutSet(
                    type = SetType.entries.firstOrNull { it.name == set.type } ?: SetType.NORMAL,
                    weightKg = set.weightKg,
                    reps = set.reps,
                    rpe = set.rpe,
                    distanceMeters = set.distanceMeters,
                    durationSeconds = set.durationSeconds,
                    completedAt = set.completedAt?.let(Instant::parse),
                )
            },
        )
    },
)

fun Routine.toBackup() = BackupRoutine(
    name = name,
    exercises = exercises.map { BackupRoutineExercise(it.exerciseId, it.sets, it.reps, it.weightKg, it.restSeconds) },
)

fun BackupRoutine.toRoutine() = Routine(
    name = name,
    exercises = exercises.map { RoutineExercise(it.exerciseId, it.sets, it.reps, it.weightKg, it.restSeconds) },
)

fun BackupCustomExercise.kindOrDefault(): ExerciseKind = ExerciseKind.entries.firstOrNull { it.name == kind } ?: ExerciseKind.WEIGHT_REPS
