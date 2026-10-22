package io.fitgymlog.app.model

import java.time.Instant

enum class ExerciseKind {
    WEIGHT_REPS,
    BODYWEIGHT_REPS,
    WEIGHTED_BODYWEIGHT,
    ASSISTED_BODYWEIGHT,
    DURATION,
    CARDIO,
}

val ExerciseKind.isTimed: Boolean get() = this == ExerciseKind.DURATION || this == ExerciseKind.CARDIO

fun ExerciseKind.defaultSets(): Int = if (this == ExerciseKind.CARDIO) 1 else 3

fun ExerciseKind.defaultRestSeconds(setting: Int): Int = if (this == ExerciseKind.CARDIO) 0 else setting

enum class SetType { NORMAL, WARMUP, DROP, FAILURE }

enum class Equipment { BARBELL, DUMBBELL, MACHINE, CABLE, BODYWEIGHT, OTHER }

enum class WorkoutSource { APP, HEVY, STRONG, BACKUP }

enum class WeightUnit { KG, LB }

/**
 * Built-in exercises carry a string resource so their name follows the app language; custom ones
 * keep the name the user typed.
 */
data class NewExercise(val name: String, val kind: ExerciseKind, val links: List<String> = emptyList())

data class Exercise(
    val id: String,
    val englishName: String,
    val kind: ExerciseKind,
    val segmentType: Int,
    val equipment: Equipment = Equipment.OTHER,
    val links: List<String> = emptyList(),
    val userLinks: List<String> = emptyList(),
    val nameRes: Int? = null,
    val customName: String? = null,
) {
    val isCustom: Boolean get() = nameRes == null
    val allLinks: List<String> get() = links + userLinks
}

data class WorkoutSet(
    val type: SetType = SetType.NORMAL,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val targetReps: Int? = reps,
    val rpe: Double? = null,
    val distanceMeters: Double? = null,
    val durationSeconds: Int? = null,
    val completedAt: Instant? = null,
    /** A cardio machine's setting: the treadmill's incline, the stair machine's or bike's level. */
    val level: Int? = null,
) {
    val isCompleted: Boolean get() = completedAt != null
}

data class WorkoutExercise(
    val key: Long,
    val exerciseId: String,
    val restSeconds: Int,
    val notes: String = "",
    val sets: List<WorkoutSet>,
)

data class Workout(
    val id: Long = 0,
    /** Stable across devices; [id] is only this phone's row. */
    val uuid: String = "",
    val title: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val notes: String = "",
    val routineId: Long? = null,
    val source: WorkoutSource = WorkoutSource.APP,
    val exercises: List<WorkoutExercise>,
    val updatedAt: Instant = startedAt,
)

data class RoutineExercise(
    val exerciseId: String,
    val sets: Int,
    val reps: Int?,
    val weightKg: Double?,
    val restSeconds: Int,
)

data class Routine(
    val id: Long = 0,
    val uuid: String = "",
    val name: String,
    val exercises: List<RoutineExercise>,
    val updatedAt: Instant = Instant.EPOCH,
)
