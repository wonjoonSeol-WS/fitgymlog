package io.fitgymlog.app.ui.exercise

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.ChartPoint
import java.time.LocalDate

enum class ProgressMetric { HEAVIEST, ESTIMATED_MAX, LEAST_ASSISTANCE, REPS, DURATION }

internal fun ExerciseKind.progressMetrics(): List<ProgressMetric> = when (this) {
    ExerciseKind.WEIGHT_REPS -> listOf(ProgressMetric.HEAVIEST, ProgressMetric.ESTIMATED_MAX)
    ExerciseKind.WEIGHTED_BODYWEIGHT -> listOf(ProgressMetric.HEAVIEST)
    ExerciseKind.ASSISTED_BODYWEIGHT -> listOf(ProgressMetric.LEAST_ASSISTANCE, ProgressMetric.REPS)
    ExerciseKind.BODYWEIGHT_REPS -> listOf(ProgressMetric.REPS)
    ExerciseKind.DURATION, ExerciseKind.CARDIO -> listOf(ProgressMetric.DURATION)
}

/** One point per session from its working sets, oldest first; warm-ups and sessions with nothing to measure are left out. */
internal fun progressPoints(sessions: List<Pair<LocalDate, List<WorkoutSet>>>, metric: ProgressMetric): List<ChartPoint> =
    sessions.sortedBy { it.first }.mapNotNull { (date, sets) -> sessionValue(sets, metric)?.let { ChartPoint(date, it) } }

private fun sessionValue(sets: List<WorkoutSet>, metric: ProgressMetric): Double? {
    val working = sets.filter { it.type != SetType.WARMUP && ((it.reps ?: 0) > 0 || (it.durationSeconds ?: 0) > 0) }
    return when (metric) {
        ProgressMetric.HEAVIEST -> working.mapNotNull { it.weightKg }.maxOrNull()
        ProgressMetric.ESTIMATED_MAX -> working.mapNotNull { set -> set.weightKg?.let { estimatedMax(it, set.reps ?: 0) } }.maxOrNull()
        ProgressMetric.LEAST_ASSISTANCE -> working.mapNotNull { it.weightKg }.minOrNull()
        ProgressMetric.REPS -> working.mapNotNull { it.reps }.maxOrNull()?.toDouble()
        ProgressMetric.DURATION -> working.mapNotNull { it.durationSeconds }.maxOrNull()?.toDouble()
    }
}

private fun ProgressMetric.beats(value: Double, other: Double): Boolean = if (this == ProgressMetric.LEAST_ASSISTANCE) value < other else value > other

/** The best session for each measure; on a tie, the first to reach it. */
internal fun records(sessions: List<Pair<LocalDate, List<WorkoutSet>>>, kind: ExerciseKind): Map<ProgressMetric, ChartPoint> =
    kind.progressMetrics().mapNotNull { metric ->
        progressPoints(sessions, metric).reduceOrNull { best, point -> if (metric.beats(point.value, best.value)) point else best }?.let { metric to it }
    }.toMap()

/**
 * For each workout, by id, the exercises it set a record in and on which measures: beating every earlier session of that
 * exercise. A first session sets none, and a workout's blocks of one exercise count as one session.
 */
internal fun recordsByWorkout(history: List<Workout>, kindOf: (String) -> ExerciseKind): Map<Long, Map<String, List<ProgressMetric>>> {
    val best = mutableMapOf<Pair<String, ProgressMetric>, Double>()
    return history.sortedBy { it.startedAt }.associate { workout ->
        val beaten = workout.exercises.groupBy({ it.exerciseId }, { it.sets }).mapValues { (exerciseId, blocks) ->
            kindOf(exerciseId).progressMetrics().filter { metric ->
                val value = sessionValue(blocks.flatten(), metric) ?: return@filter false
                val before = best[exerciseId to metric]
                val better = before == null || metric.beats(value, before)
                if (better) best[exerciseId to metric] = value
                before != null && better
            }
        }.filterValues { it.isNotEmpty() }
        workout.id to beaten
    }.filterValues { it.isNotEmpty() }
}

/** The measures with at least two sessions to draw, each with its points. */
internal fun chartableMetrics(sessions: List<Pair<LocalDate, List<WorkoutSet>>>, kind: ExerciseKind): Map<ProgressMetric, List<ChartPoint>> =
    kind.progressMetrics().associateWith { progressPoints(sessions, it) }.filterValues { it.size >= 2 }

/** Epley's formula; past [MAX_ESTIMATE_REPS] reps it drifts too far to mean anything, so those sets are not counted. */
internal fun estimatedMax(weightKg: Double, reps: Int): Double? = when {
    reps < 1 || reps > MAX_ESTIMATE_REPS -> null
    reps == 1 -> weightKg
    else -> weightKg * (1 + reps / 30.0)
}

private const val MAX_ESTIMATE_REPS = 12
