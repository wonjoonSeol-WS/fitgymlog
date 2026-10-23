package io.fitgymlog.app.coach

import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise

data class AppliedRoutineChanges(val routine: Routine, val indices: Set<Int>)

fun Routine.accepts(change: RoutineChange): Boolean {
    if (change.applied || change.routineId != id) return false
    val present = exercises.any { it.exerciseId == change.exerciseId }
    return if (change.action == ChangeAction.ADD) !present else present
}

/** Each change is checked against the routine as the earlier ones left it; an exercise planned twice changes at its first place. */
fun Routine.applying(chosen: Map<Int, RoutineChange>, added: Map<String, RoutineExercise>): AppliedRoutineChanges =
    chosen.entries.fold(AppliedRoutineChanges(this, emptySet())) { result, (index, change) ->
        val block = added[change.exerciseId]
        when {
            !result.routine.accepts(change) -> result
            change.action == ChangeAction.ADD && block == null -> result
            else -> AppliedRoutineChanges(result.routine.with(change, block), result.indices + index)
        }
    }

private fun Routine.with(change: RoutineChange, block: RoutineExercise?): Routine {
    val at = exercises.indexOfFirst { it.exerciseId == change.exerciseId }
    val changed = when (change.action) {
        ChangeAction.ADD -> exercises + listOfNotNull(block)
        ChangeAction.REMOVE -> exercises.filterIndexed { index, _ -> index != at }
        ChangeAction.ADJUST -> exercises.mapIndexed { index, planned ->
            if (index != at) {
                planned
            } else {
                planned.copy(
                    sets = change.sets ?: planned.sets,
                    reps = change.reps ?: planned.reps,
                    durationSeconds = change.minutes?.let { it * 60 } ?: planned.durationSeconds,
                )
            }
        }
    }
    return copy(exercises = changed)
}
