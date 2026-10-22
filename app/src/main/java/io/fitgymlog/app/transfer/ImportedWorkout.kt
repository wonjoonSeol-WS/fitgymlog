package io.fitgymlog.app.transfer

import io.fitgymlog.app.model.WorkoutSet
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

/**
 * Groups set rows into workouts and, within each workout, consecutive rows of one exercise into one entry.
 * Notes sit on every set row and can differ between rows, so an entry keeps each distinct one.
 */
internal fun <K> groupRows(rows: List<ParsedRow<K>>): List<ImportedWorkout> = rows
    .groupBy { it.workoutKey }
    .values
    .map { workoutRows ->
        val runs = mutableListOf<MutableList<ParsedRow<K>>>()
        for (row in workoutRows) {
            val run = runs.lastOrNull()
            if (run != null && run.last().exerciseName == row.exerciseName) run += row else runs += mutableListOf(row)
        }
        val exercises = runs.map { run ->
            val notes = run.map { it.exerciseNotes }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
            ImportedExercise(run.first().exerciseName, notes, run.map { it.set })
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
