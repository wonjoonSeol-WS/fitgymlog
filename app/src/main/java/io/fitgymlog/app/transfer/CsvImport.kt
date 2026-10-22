package io.fitgymlog.app.transfer

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.NewExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.WorkoutSource

sealed interface ExerciseChoice {
    data class Existing(val id: String) : ExerciseChoice

    data class Create(val exercise: NewExercise) : ExerciseChoice
}

internal fun pendingCsv(source: WorkoutSource, result: ParseResult, matcher: ExerciseMatcher): PendingImport.Csv {
    val names = result.workouts.flatMap { workout -> workout.exercises.map { it.name } }.distinct()
    val matched = names.mapNotNull { name -> matcher.match(name)?.let { name to it } }.toMap()
    val unmatched = names.filter { it !in matched }.distinctBy(::sameExerciseKey)
    return PendingImport.Csv(source, result.workouts, result.errors, matched, unmatched)
}

/** [keepNew] runs first, so a name used only by workouts already saved never becomes a custom exercise. */
internal suspend fun importWorkouts(
    pending: PendingImport.Csv,
    choices: Map<String, ExerciseChoice>,
    keepNew: suspend (List<ImportedWorkout>) -> List<ImportedWorkout>,
    createCustom: suspend (NewExercise) -> String,
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
                is ExerciseChoice.Create -> createCustom(choice.exercise).also { created++ }
                null -> createCustom(NewExercise(group.first().name, inferKind(group.flatMap { it.sets }))).also { created++ }
            }
            group.forEach { ids[it.name] = id }
        }
    val added = save(fresh, ids)
    return ImportSummary(added, pending.workouts.size - added, created)
}

internal fun inferKind(sets: List<WorkoutSet>): ExerciseKind = when {
    sets.any { (it.weightKg ?: 0.0) > 0 } -> ExerciseKind.WEIGHT_REPS
    sets.any { it.reps != null } -> ExerciseKind.BODYWEIGHT_REPS
    sets.any { it.distanceMeters != null || (it.durationSeconds ?: 0) >= CARDIO_SECONDS } -> ExerciseKind.CARDIO
    sets.any { it.durationSeconds != null } -> ExerciseKind.DURATION
    else -> ExerciseKind.WEIGHT_REPS
}

private const val CARDIO_SECONDS = 5 * 60

private fun sameExerciseKey(name: String): String = ExerciseMatcher.normalize(name).ifEmpty { name }
