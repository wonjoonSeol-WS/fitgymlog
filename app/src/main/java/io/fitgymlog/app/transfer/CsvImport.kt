package io.fitgymlog.app.transfer

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.WorkoutSource

/** What the user picked for an exercise name the import did not recognize. */
sealed interface ExerciseChoice {
    data class Existing(val id: String) : ExerciseChoice

    data class Create(val name: String, val kind: ExerciseKind, val link: String?) : ExerciseChoice
}

data class NewCustomExercise(val name: String, val kind: ExerciseKind, val links: List<String>)

internal fun pendingCsv(source: WorkoutSource, result: ParseResult, matcher: ExerciseMatcher): PendingImport.Csv {
    val names = result.workouts.flatMap { workout -> workout.exercises.map { it.name } }.distinct()
    val matched = names.mapNotNull { name -> matcher.match(name)?.let { name to it } }.toMap()
    val unmatched = names.filter { it !in matched }.distinctBy(::sameExerciseKey)
    return PendingImport.Csv(source, result.workouts, result.errors, matched, unmatched)
}

/**
 * [keepNew] drops the workouts that are already saved, so a name only they use never becomes a custom
 * exercise. Names without a choice become one custom exercise per spelling group, of a kind read from the sets.
 */
internal suspend fun importWorkouts(
    pending: PendingImport.Csv,
    choices: Map<String, ExerciseChoice>,
    keepNew: suspend (List<ImportedWorkout>) -> List<ImportedWorkout>,
    createCustom: suspend (NewCustomExercise) -> String,
    save: suspend (workouts: List<ImportedWorkout>, exerciseIds: Map<String, String>) -> Int,
): ImportSummary {
    val fresh = keepNew(pending.workouts)
    val chosen = choices.mapKeys { sameExerciseKey(it.key) }
    val ids = pending.matched.toMutableMap()
    var created = 0
    fresh.flatMap { it.exercises }
        .filter { it.name !in pending.matched }
        .groupBy { sameExerciseKey(it.name) }
        .forEach { (key, group) ->
            val id = when (val choice = chosen[key]) {
                is ExerciseChoice.Existing -> choice.id
                is ExerciseChoice.Create -> createCustom(NewCustomExercise(choice.name, choice.kind, listOfNotNull(choice.link))).also { created++ }
                null -> createCustom(NewCustomExercise(group.first().name, inferKind(group.flatMap { it.sets }), emptyList())).also { created++ }
            }
            group.forEach { ids[it.name] = id }
        }
    val added = save(fresh, ids)
    return ImportSummary(added, pending.workouts.size - added, created)
}

internal fun inferKind(sets: List<WorkoutSet>): ExerciseKind = when {
    sets.any { (it.weightKg ?: 0.0) > 0 } -> ExerciseKind.WEIGHT_REPS
    sets.any { it.reps != null } -> ExerciseKind.BODYWEIGHT_REPS
    sets.any { it.durationSeconds != null } -> ExerciseKind.DURATION
    else -> ExerciseKind.WEIGHT_REPS
}

private fun sameExerciseKey(name: String): String = ExerciseMatcher.normalize(name).ifEmpty { name }
