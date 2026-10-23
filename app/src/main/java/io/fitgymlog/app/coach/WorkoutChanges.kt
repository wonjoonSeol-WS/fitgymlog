package io.fitgymlog.app.coach

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.defaultRestSeconds
import io.fitgymlog.app.model.defaultSets
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.startingSets
import java.time.Instant

data class NewBlock(val sets: List<WorkoutSet>, val restSeconds: Int)

data class AppliedChanges(val workout: ActiveWorkout, val indices: Set<Int>)

internal fun setsToAdd(change: Change, kind: ExerciseKind, previous: List<WorkoutSet>): List<WorkoutSet> {
    val count = change.sets ?: previous.count { it.type == SetType.NORMAL }.takeIf { it > 0 } ?: kind.defaultSets()
    val planned = RoutineExercise(change.exerciseId, count, change.reps, weightKg = null, restSeconds = 0, durationSeconds = change.minutes?.let { it * 60 })
    return startingSets(planned, kind, previous)
}

internal fun restToAdd(change: Change, kind: ExerciseKind, defaultRestSeconds: Int): Int = change.restSeconds ?: kind.defaultRestSeconds(defaultRestSeconds)

fun ActiveWorkout.accepts(change: Change): Boolean {
    if (change.applied || change.workoutUuid != workout.uuid) return false
    val present = exercises.any { it.exerciseId == change.exerciseId }
    return if (change.action == ChangeAction.ADD) !present else present
}

/** Each change is checked against the workout as the earlier ones left it, so two adds of one exercise apply once; one that changes nothing is not applied. */
fun ActiveWorkout.applying(chosen: Map<Int, Change>, added: Map<String, NewBlock>, now: Instant): AppliedChanges =
    chosen.entries.fold(AppliedChanges(this, emptySet())) { result, (index, change) ->
        val block = added[change.exerciseId]
        if (!result.workout.accepts(change) || change.action == ChangeAction.ADD && block == null) return@fold result
        val next = result.workout.withChange(change, block, now)
        if (next == result.workout) result else AppliedChanges(next, result.indices + index)
    }

private fun ActiveWorkout.withChange(change: Change, block: NewBlock?, now: Instant): ActiveWorkout = when (change.action) {
    ChangeAction.ADJUST -> (change.sets?.let { withWorkingSets(change.exerciseId, it) } ?: this)
        .applyTarget(change.exerciseId, change.weightKg, change.reps, change.minutes?.let { it * 60 })
        .let { adjusted -> change.restSeconds?.let { adjusted.withRest(change.exerciseId, it) } ?: adjusted }
    ChangeAction.REMOVE -> withoutOpenSets(change.exerciseId)
    ChangeAction.ADD -> block?.let { addExercise(change.exerciseId, it.sets, it.restSeconds, now) } ?: this
}
