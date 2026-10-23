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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Everything the app stores, in one versioned JSON file, for moving phones or keeping a copy. */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: String,
    val customExercises: List<BackupCustomExercise>,
    val routines: List<BackupRoutine>,
    val workouts: List<BackupWorkout>,
    val exerciseLinks: List<BackupLink> = emptyList(),
) {
    companion object {
        const val FORMAT = "fitgymlog-backup"
        const val VERSION = 2
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

        fun looksLike(text: String): Boolean = text.removePrefix(BOM).trimStart().let { it.startsWith("{") && FORMAT in it.take(200) }

        fun decode(text: String): BackupFile {
            val root = malformedOnError { json.parseToJsonElement(text.removePrefix(BOM)) }
            val version = ((root as? JsonObject)?.get("version") as? JsonPrimitive)?.intOrNull
            if (version != null && version > VERSION) {
                throw TransferException(TransferFailure.NEWER_BACKUP, "backup version $version is newer than $VERSION")
            }
            val backup = malformedOnError { json.decodeFromJsonElement(serializer(), root) }
            if (backup.format != FORMAT) throw TransferException(TransferFailure.MALFORMED_BACKUP, "format is ${backup.format}")
            return backup
        }

        fun encode(backup: BackupFile): String = json.encodeToString(serializer(), backup)

        private inline fun <T> malformedOnError(block: () -> T): T = try {
            block()
        } catch (e: IllegalArgumentException) {
            throw TransferException(TransferFailure.MALFORMED_BACKUP, e.message.orEmpty(), e)
        }
    }
}

@Serializable
data class BackupCustomExercise(val id: String, val name: String, val kind: String, val links: List<String>)

/** A link the user added to a built-in exercise; custom exercises carry their own links. */
@Serializable
data class BackupLink(val exerciseId: String, val url: String)

@Serializable
data class BackupRoutine(val name: String, val exercises: List<BackupRoutineExercise>)

@Serializable
data class BackupRoutineExercise(
    val exerciseId: String,
    val sets: Int,
    val reps: Int?,
    val weightKg: Double?,
    val restSeconds: Int,
    val durationSeconds: Int? = null,
    val level: Int? = null,
    val speedKmh: Double? = null,
)

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
    val level: Int? = null,
    val targetReps: Int? = null,
    val speedKmh: Double? = null,
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
                BackupSet(
                    type = set.type.name,
                    weightKg = set.weightKg,
                    reps = set.reps,
                    rpe = set.rpe,
                    distanceMeters = set.distanceMeters,
                    durationSeconds = set.durationSeconds,
                    completedAt = set.completedAt?.toString(),
                    level = set.level,
                    targetReps = set.targetReps,
                    speedKmh = set.speedKmh,
                )
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
                    targetReps = set.targetReps,
                    rpe = set.rpe,
                    distanceMeters = set.distanceMeters,
                    durationSeconds = set.durationSeconds,
                    completedAt = set.completedAt?.let(Instant::parse),
                    level = set.level,
                    speedKmh = set.speedKmh,
                )
            },
        )
    },
)

fun Routine.toBackup() = BackupRoutine(
    name = name,
    exercises = exercises.map {
        BackupRoutineExercise(
            exerciseId = it.exerciseId,
            sets = it.sets,
            reps = it.reps,
            weightKg = it.weightKg,
            restSeconds = it.restSeconds,
            durationSeconds = it.durationSeconds,
            level = it.level,
            speedKmh = it.speedKmh,
        )
    },
)

fun BackupRoutine.toRoutine() = Routine(
    name = name,
    exercises = exercises.map {
        RoutineExercise(
            exerciseId = it.exerciseId,
            sets = it.sets,
            reps = it.reps,
            weightKg = it.weightKg,
            restSeconds = it.restSeconds,
            durationSeconds = it.durationSeconds,
            level = it.level,
            speedKmh = it.speedKmh,
        )
    },
)

fun BackupCustomExercise.kindOrDefault(): ExerciseKind = ExerciseKind.entries.firstOrNull { it.name == kind } ?: ExerciseKind.WEIGHT_REPS
