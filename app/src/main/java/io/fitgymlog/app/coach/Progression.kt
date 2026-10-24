package io.fitgymlog.app.coach

import io.fitgymlog.app.model.Equipment
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.isTimed
import io.fitgymlog.app.ui.fromDisplayWeight
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * [HARD_SETS]: every rep was done, but at or near failure by the logged RPE, so the weight holds, or for bodyweight work the reps.
 * [MORE_REPS]: every rep was done in a rep range not yet at its top, so the weight holds and the reps go up one.
 */
enum class TargetReason { FIRST_TIME, ALL_REPS_DONE, MORE_REPS, HARD_SETS, MISSED_REPS, STALLED, SAME_AS_LAST }

/** What the rules suggest for an exercise today, and how far a coach model may move away from it. */
data class RuleTarget(
    val exerciseId: String,
    val reason: TargetReason,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val lastWeightKg: Double? = null,
    val minWeightKg: Double? = null,
    val maxWeightKg: Double? = null,
    val stepKg: Double? = null,
)

/**
 * [target] takes finished sessions, most recent first, whose sets were all done. Heavy sets of up to [HEAVY_REPS] reps add
 * weight once every rep is done, as StrongLifts does. With more reps planned, every rep done adds a rep instead, up to
 * [REP_RANGE] over the plan, and only then weight, back at the planned reps: the double progression of rep-range programs.
 */
object Progression {
    /** How many past sessions [target] needs to see a stall. */
    const val HISTORY_SESSIONS = 3
    private const val STALL_SESSIONS = HISTORY_SESSIONS
    private const val NEAR_FAILURE_RPE = 9.5
    private const val DELOAD = 0.9
    private const val LOWEST_SHARE = 0.8
    private const val HIGHEST_STEPS = 2
    private const val HEAVY_REPS = 6
    private const val REP_RANGE = 4

    /** [plannedReps] is the routine's reps for the exercise, the bottom of its rep range; null when no routine plans it. */
    fun target(exercise: Exercise, sessions: List<List<WorkoutSet>>, unit: WeightUnit, plannedReps: Int? = null): RuleTarget {
        val working = sessions.map { sets -> sets.filter { it.type == SetType.NORMAL } }.filter { it.isNotEmpty() }
        val last = working.firstOrNull() ?: return RuleTarget(exercise.id, TargetReason.FIRST_TIME)
        return when {
            exercise.kind.isTimed -> RuleTarget(exercise.id, TargetReason.SAME_AS_LAST)
            exercise.kind == ExerciseKind.BODYWEIGHT_REPS -> bodyweight(exercise.id, last)
            else -> weighted(exercise, working, unit, plannedReps)
        }
    }

    fun stepKg(exercise: Exercise, unit: WeightUnit): Double {
        val large = exercise.largeIncrements || exercise.equipment == Equipment.MACHINE
        return when (unit) {
            WeightUnit.KG -> when {
                large -> 5.0
                exercise.equipment == Equipment.DUMBBELL -> 2.0
                else -> 2.5
            }
            WeightUnit.LB -> fromDisplayWeight(if (large) 10.0 else 5.0, unit)
        }
    }

    private fun bodyweight(exerciseId: String, last: List<WorkoutSet>): RuleTarget {
        val reps = last.first().let { it.targetReps ?: it.reps } ?: return RuleTarget(exerciseId, TargetReason.FIRST_TIME)
        val reason = when {
            !succeeded(last) -> TargetReason.MISSED_REPS
            nearFailure(last) -> TargetReason.HARD_SETS
            else -> TargetReason.ALL_REPS_DONE
        }
        return RuleTarget(
            exerciseId = exerciseId,
            reason = reason,
            reps = if (reason == TargetReason.ALL_REPS_DONE) reps + 1 else reps,
        )
    }

    private data class Next(val reason: TargetReason, val weightKg: Double, val reps: Int?)

    /** For assisted work the weight is help from the machine, so progress means less of it. */
    private fun weighted(exercise: Exercise, working: List<List<WorkoutSet>>, unit: WeightUnit, plannedReps: Int?): RuleTarget {
        val assisted = exercise.kind == ExerciseKind.ASSISTED_BODYWEIGHT
        val last = working.first()
        val lastWeight = hardest(last, assisted) ?: return RuleTarget(exercise.id, TargetReason.FIRST_TIME)
        val step = stepKg(exercise, unit)
        val top = topSets(last, assisted)
        val reps = top.first().let { it.targetReps ?: it.reps }
        val stalled = working.size >= STALL_SESSIONS &&
            working.take(STALL_SESSIONS).all { session -> sameWeight(hardest(session, assisted), lastWeight) && !succeeded(topSets(session, assisted)) }
        val forward = if (assisted) -step else step
        val heavier = tidy((lastWeight + forward).coerceAtLeast(0.0))
        val next = when {
            succeeded(top) && nearFailure(top) -> Next(TargetReason.HARD_SETS, lastWeight, reps)
            succeeded(top) && (plannedReps ?: reps ?: 0) <= HEAVY_REPS -> Next(TargetReason.ALL_REPS_DONE, heavier, reps)
            succeeded(top) -> climb(lastWeight, heavier, reps, plannedReps)
            stalled -> Next(TargetReason.STALLED, if (assisted) roundUp(lastWeight / DELOAD, step) else roundDown(lastWeight * DELOAD, step), plannedReps ?: reps)
            else -> Next(TargetReason.MISSED_REPS, lastWeight, reps)
        }
        val easiest = if (assisted) roundUp(lastWeight / LOWEST_SHARE, step) else roundDown(lastWeight * LOWEST_SHARE, step)
        val furthest = (lastWeight + HIGHEST_STEPS * forward).coerceAtLeast(0.0)
        return RuleTarget(
            exerciseId = exercise.id,
            reason = next.reason,
            weightKg = next.weightKg,
            reps = next.reps,
            lastWeightKg = lastWeight,
            minWeightKg = minOf(easiest, furthest),
            maxWeightKg = maxOf(easiest, furthest),
            stepKg = step,
        )
    }

    /**
     * A rep range done in full: one more rep, from the plan at least, until [REP_RANGE] over it, then the next weight back at
     * the plan. With no plan the range has no known top, so the set repeats rather than getting heavier every session.
     */
    private fun climb(lastWeight: Double, heavier: Double, reps: Int?, plannedReps: Int?): Next {
        if (plannedReps == null || reps == null) return Next(TargetReason.SAME_AS_LAST, lastWeight, reps)
        if (reps >= plannedReps + REP_RANGE) return Next(TargetReason.ALL_REPS_DONE, heavier, plannedReps)
        return Next(TargetReason.MORE_REPS, lastWeight, maxOf(reps + 1, plannedReps))
    }

    private fun hardest(sets: List<WorkoutSet>, assisted: Boolean): Double? =
        sets.mapNotNull { it.weightKg }.let { if (assisted) it.minOrNull() else it.maxOrNull() }

    private fun topSets(sets: List<WorkoutSet>, assisted: Boolean): List<WorkoutSet> = hardest(sets, assisted).let { top -> sets.filter { sameWeight(it.weightKg, top) } }

    /** Weights entered in pounds or applied from the coach differ from typed ones in the last bits, so they are compared by the gram. */
    private fun sameWeight(a: Double?, b: Double?): Boolean = a?.let { (it * 1000).roundToLong() } == b?.let { (it * 1000).roundToLong() }

    /** A set without a plan counts its own reps as the plan; within a session the first set's plan is the bar. */
    private fun succeeded(sets: List<WorkoutSet>): Boolean {
        val planned = sets.first().let { it.targetReps ?: it.reps } ?: return false
        return sets.all { (it.reps ?: 0) >= (it.targetReps ?: planned) }
    }

    /** Only a logged RPE can say a done set was a grind; without one, done means up, as it always has. */
    private fun nearFailure(sets: List<WorkoutSet>): Boolean = sets.any { (it.rpe ?: 0.0) >= NEAR_FAILURE_RPE }

    private fun roundDown(kg: Double, step: Double): Double = tidy(floor(kg / step + 1e-9) * step)

    private fun roundUp(kg: Double, step: Double): Double = tidy(ceil(kg / step - 1e-9) * step)

    private fun tidy(kg: Double): Double = (kg * 1000).roundToLong() / 1000.0
}
