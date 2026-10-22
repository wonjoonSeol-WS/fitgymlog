package io.gymlog.app.model

import java.time.Instant

enum class ExerciseKind {
    WEIGHT_REPS,
    BODYWEIGHT_REPS,
    WEIGHTED_BODYWEIGHT,
    ASSISTED_BODYWEIGHT,
    DURATION,
}

enum class SetType { NORMAL, WARMUP, DROP, FAILURE }

enum class WorkoutSource { APP, HEVY, STRONG, BACKUP }

data class Exercise(
    val id: String,
    val name: String,
    val englishName: String,
    val kind: ExerciseKind,
    val links: List<String>,
    val isCustom: Boolean,
)

data class WorkoutSet(
    val type: SetType = SetType.NORMAL,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val targetReps: Int? = reps,
    val rpe: Double? = null,
    val distanceMeters: Double? = null,
    val durationSeconds: Int? = null,
    val completedAt: Instant? = null,
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
    val title: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val notes: String = "",
    val routineId: Long? = null,
    val source: WorkoutSource = WorkoutSource.APP,
    val exercises: List<WorkoutExercise>,
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
    val name: String,
    val exercises: List<RoutineExercise>,
)
