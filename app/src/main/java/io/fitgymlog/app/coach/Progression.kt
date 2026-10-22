package io.fitgymlog.app.coach

import io.fitgymlog.app.model.Equipment
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.isTimed
import io.fitgymlog.app.ui.fromDisplayWeight
import kotlin.math.floor
import kotlin.math.roundToLong

enum class TargetReason { FIRST_TIME, ALL_REPS_DONE, MISSED_REPS, STALLED, SAME_AS_LAST }

/** What the rules suggest for an exercise today, and how far a coach model may move away from it. */
data class RuleTarget(
    val exerciseId: String,
    val reason: TargetReason,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val lastWeightKg: Double? = null,
    val lastReps: Int? = null,
    val minWeightKg: Double? = null,
    val maxWeightKg: Double? = null,
    val stepKg: Double? = null,
)

/**
 * Linear progression as StrongLifts runs it: every planned rep at a weight earns one step up, a miss
 * repeats the weight, and three sessions in a row without a success at the same weight drop it by 10%.
 * [sessions] are the exercise's past sets, most recent session first.
 */
object Progression {
    private const val STALL_SESSIONS = 3
    private const val DELOAD = 0.9
    private const val LOWEST_SHARE = 0.8
    private const val HIGHEST_STEPS = 2

    fun target(exercise: Exercise, sessions: List<List<WorkoutSet>>, unit: WeightUnit): RuleTarget {
        val working = sessions.map { sets -> sets.filter { it.type == SetType.NORMAL && it.isCompleted } }.filter { it.isNotEmpty() }
        val last = working.firstOrNull() ?: return RuleTarget(exercise.id, TargetReason.FIRST_TIME)
        return when {
            exercise.kind.isTimed -> RuleTarget(exercise.id, TargetReason.SAME_AS_LAST)
            exercise.kind == ExerciseKind.BODYWEIGHT_REPS -> bodyweight(exercise.id, last)
            else -> weighted(exercise, working, unit)
        }
    }

    fun stepKg(exercise: Exercise, unit: WeightUnit): Double {
        val deadlift = exercise.id == "deadlift_barbell"
        return when (unit) {
            WeightUnit.KG -> when {
                deadlift -> 5.0
                exercise.equipment == Equipment.DUMBBELL -> 2.0
                exercise.equipment == Equipment.MACHINE -> 5.0
                else -> 2.5
            }
            WeightUnit.LB -> fromDisplayWeight(if (deadlift || exercise.equipment == Equipment.MACHINE) 10.0 else 5.0, unit)
        }
    }

    private fun bodyweight(exerciseId: String, last: List<WorkoutSet>): RuleTarget {
        val reps = last.first().reps ?: return RuleTarget(exerciseId, TargetReason.FIRST_TIME)
        val done = succeeded(last)
        return RuleTarget(
            exerciseId = exerciseId,
            reason = if (done) TargetReason.ALL_REPS_DONE else TargetReason.MISSED_REPS,
            reps = if (done) reps + 1 else reps,
            lastReps = reps,
        )
    }

    private fun weighted(exercise: Exercise, working: List<List<WorkoutSet>>, unit: WeightUnit): RuleTarget {
        val last = working.first()
        val lastWeight = last.mapNotNull { it.weightKg }.maxOrNull() ?: return RuleTarget(exercise.id, TargetReason.FIRST_TIME)
        val step = stepKg(exercise, unit)
        val topSets = last.filter { it.weightKg == lastWeight }
        val reps = topSets.first().let { it.targetReps ?: it.reps }
        val stalled = working.size >= STALL_SESSIONS &&
            working.take(STALL_SESSIONS).all { session -> session.mapNotNull { it.weightKg }.maxOrNull() == lastWeight && !succeeded(session) }
        val (reason, weight) = when {
            succeeded(topSets) -> TargetReason.ALL_REPS_DONE to lastWeight + step
            stalled -> TargetReason.STALLED to roundDown(lastWeight * DELOAD, step)
            else -> TargetReason.MISSED_REPS to lastWeight
        }
        return RuleTarget(
            exerciseId = exercise.id,
            reason = reason,
            weightKg = weight,
            reps = reps,
            lastWeightKg = lastWeight,
            lastReps = reps,
            minWeightKg = roundDown(lastWeight * LOWEST_SHARE, step),
            maxWeightKg = lastWeight + HIGHEST_STEPS * step,
            stepKg = step,
        )
    }

    /** A set without a plan counts its own reps as the plan; within a session the first set's plan is the bar. */
    private fun succeeded(sets: List<WorkoutSet>): Boolean {
        val planned = sets.first().let { it.targetReps ?: it.reps } ?: return false
        return sets.all { (it.reps ?: 0) >= (it.targetReps ?: planned) }
    }

    private fun roundDown(kg: Double, step: Double): Double = (floor(kg / step + 1e-9) * step).let { (it * 1000).roundToLong() / 1000.0 }
}
