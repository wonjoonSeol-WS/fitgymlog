package io.fitgymlog.app.data

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.WorkoutSource
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.SetRef
import java.time.Instant

fun WorkoutWithExercises.toWorkout(): Workout = Workout(
    id = workout.id,
    uuid = workout.uuid,
    updatedAt = Instant.ofEpochMilli(workout.updatedAt),
    title = workout.title,
    startedAt = Instant.ofEpochMilli(workout.startedAt),
    endedAt = workout.endedAt?.let(Instant::ofEpochMilli),
    notes = workout.notes,
    routineId = workout.routineId,
    source = enumValueOrDefault(workout.source, WorkoutSource.APP),
    exercises = exercises.sortedBy { it.exercise.position }.map { (exercise, sets) ->
        WorkoutExercise(
            key = exercise.key,
            exerciseId = exercise.exerciseId,
            restSeconds = exercise.restSeconds,
            notes = exercise.notes,
            sets = sets.sortedBy { it.position }.map { it.toSet() },
        )
    },
)

fun WorkoutWithExercises.toActiveWorkout(): ActiveWorkout = ActiveWorkout(
    workout = toWorkout(),
    focus = workout.focus,
    shownCompletedSet = if (workout.shownExerciseIndex != null && workout.shownSetIndex != null) {
        SetRef(workout.shownExerciseIndex, workout.shownSetIndex)
    } else {
        null
    },
    restEndsAt = workout.restEndsAt?.let(Instant::ofEpochMilli),
    lastActivityAt = Instant.ofEpochMilli(workout.lastActivityAt),
)

fun Workout.toGraph(lastActivityAt: Instant = endedAt ?: startedAt): WorkoutGraph = WorkoutGraph(
    workout = WorkoutEntity(
        id = id,
        uuid = uuid,
        updatedAt = updatedAt.toEpochMilli(),
        title = title,
        startedAt = startedAt.toEpochMilli(),
        endedAt = endedAt?.toEpochMilli(),
        notes = notes,
        routineId = routineId,
        source = source.name,
        lastActivityAt = lastActivityAt.toEpochMilli(),
    ),
    exercises = exercises.mapIndexed { position, exercise ->
        WorkoutExerciseEntity(
            workoutId = id,
            position = position,
            key = exercise.key,
            exerciseId = exercise.exerciseId,
            restSeconds = exercise.restSeconds,
            notes = exercise.notes,
        ) to exercise.sets.mapIndexed { setPosition, set -> set.toEntity(setPosition) }
    },
)

fun ActiveWorkout.toGraph(): WorkoutGraph {
    val graph = workout.toGraph(lastActivityAt)
    return graph.copy(
        workout = graph.workout.copy(
            focus = focus,
            shownExerciseIndex = shownCompletedSet?.exerciseIndex,
            shownSetIndex = shownCompletedSet?.setIndex,
            restEndsAt = restEndsAt?.toEpochMilli(),
        ),
    )
}

fun WorkoutSetEntity.toPreviousSet(): WorkoutSet = toSet().copy(completedAt = null)

private fun WorkoutSetEntity.toSet() = WorkoutSet(
    type = enumValueOrDefault(type, SetType.NORMAL),
    weightKg = weightKg,
    reps = reps,
    targetReps = targetReps,
    rpe = rpe,
    distanceMeters = distanceMeters,
    durationSeconds = durationSeconds,
    completedAt = completedAt?.let(Instant::ofEpochMilli),
    level = level,
)

private fun WorkoutSet.toEntity(position: Int) = WorkoutSetEntity(
    workoutExerciseId = 0,
    position = position,
    type = type.name,
    weightKg = weightKg,
    reps = reps,
    targetReps = targetReps,
    rpe = rpe,
    distanceMeters = distanceMeters,
    durationSeconds = durationSeconds,
    completedAt = completedAt?.toEpochMilli(),
    level = level,
)

fun RoutineWithExercises.toRoutine(): Routine = Routine(
    id = routine.id,
    uuid = routine.uuid,
    updatedAt = Instant.ofEpochMilli(routine.updatedAt),
    name = routine.name,
    exercises = exercises.sortedBy { it.position }.map {
        RoutineExercise(it.exerciseId, it.sets, it.reps, it.weightKg, it.restSeconds)
    },
)

fun RoutineExercise.toEntity(): RoutineExerciseEntity = RoutineExerciseEntity(
    routineId = 0,
    position = 0,
    exerciseId = exerciseId,
    sets = sets,
    reps = reps,
    weightKg = weightKg,
    restSeconds = restSeconds,
)

fun CustomExerciseEntity.kindOrDefault(): ExerciseKind = enumValueOrDefault(kind, ExerciseKind.WEIGHT_REPS)

inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
