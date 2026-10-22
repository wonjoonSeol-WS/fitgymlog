package io.gymlog.app.transfer

import io.gymlog.app.model.WorkoutSet
import java.time.LocalDateTime

/** A workout read from another app's file, before its exercise names are matched to ours. */
data class ImportedWorkout(
    val title: String,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val notes: String,
    val exercises: List<ImportedExercise>,
)

data class ImportedExercise(
    val name: String,
    val notes: String,
    val sets: List<WorkoutSet>,
)

data class RowError(val rowNumber: Int, val message: String)

data class ParseResult(
    val workouts: List<ImportedWorkout>,
    val errors: List<RowError>,
)

internal const val KG_PER_LB = 0.45359237
internal const val METERS_PER_MILE = 1609.344

/** Groups set rows into workouts and, within each workout, consecutive rows of one exercise into one entry. */
internal fun <K> groupRows(rows: List<ParsedRow<K>>): List<ImportedWorkout> = rows
    .groupBy { it.workoutKey }
    .values
    .map { workoutRows ->
        val exercises = mutableListOf<ImportedExercise>()
        for (row in workoutRows) {
            val last = exercises.lastOrNull()
            if (last != null && last.name == row.exerciseName) {
                exercises[exercises.lastIndex] = last.copy(sets = last.sets + row.set)
            } else {
                exercises += ImportedExercise(row.exerciseName, row.exerciseNotes, listOf(row.set))
            }
        }
        val first = workoutRows.first()
        ImportedWorkout(first.title, first.startedAt, first.endedAt, first.notes, exercises)
    }

internal data class ParsedRow<K>(
    val workoutKey: K,
    val title: String,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val notes: String,
    val exerciseName: String,
    val exerciseNotes: String,
    val set: WorkoutSet,
)
