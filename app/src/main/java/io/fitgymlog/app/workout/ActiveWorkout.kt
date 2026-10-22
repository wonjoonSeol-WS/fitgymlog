package io.fitgymlog.app.workout

import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import java.time.Duration
import java.time.Instant

data class SetRef(val exerciseIndex: Int, val setIndex: Int)

/** What the band shows: the set just completed while resting, otherwise the next set of the focused exercise. */
data class BandView(
    val exerciseId: String,
    val setNumber: Int,
    val setCount: Int,
    val weightKg: Double?,
    val reps: Int?,
    val durationSeconds: Int?,
    val isCompleted: Boolean,
    val isExerciseDone: Boolean,
)

/**
 * An in-progress workout. Every change returns a new value, so the phone screen and the band remote
 * can share one source of truth.
 */
data class ActiveWorkout(
    val workout: Workout,
    val focus: Int = 0,
    val shownCompletedSet: SetRef? = null,
    val restEndsAt: Instant? = null,
    val lastActivityAt: Instant = workout.startedAt,
) {
    val exercises: List<WorkoutExercise> get() = workout.exercises

    fun bandView(): BandView? {
        val exercise = exercises.getOrNull(focus) ?: return null
        val ref = shownCompletedSet ?: nextSetOf(focus)
        if (ref == null) {
            return BandView(
                exerciseId = exercise.exerciseId,
                setNumber = exercise.sets.size,
                setCount = exercise.sets.size,
                weightKg = null,
                reps = null,
                durationSeconds = null,
                isCompleted = true,
                isExerciseDone = true,
            )
        }
        val set = exercises[ref.exerciseIndex].sets[ref.setIndex]
        return BandView(
            exerciseId = exercises[ref.exerciseIndex].exerciseId,
            setNumber = ref.setIndex + 1,
            setCount = exercises[ref.exerciseIndex].sets.size,
            weightKg = set.weightKg,
            reps = set.reps ?: set.targetReps,
            durationSeconds = set.durationSeconds,
            isCompleted = set.isCompleted,
            isExerciseDone = false,
        )
    }

    fun focusedNextSet(): SetRef? = nextSetOf(focus)

    /**
     * With [expected], completes that set only if it is still open, so the screen and the band
     * completing the same set at once do not also tick the one after it.
     */
    fun completeFocusedSet(now: Instant, expected: SetRef? = null): ActiveWorkout {
        val ref = expected ?: nextSetOf(focus) ?: return this
        if (setAt(ref)?.isCompleted != false) return this
        return complete(ref, now)
    }

    fun toggleSet(ref: SetRef, now: Instant): ActiveWorkout {
        val set = setAt(ref) ?: return this
        if (!set.isCompleted) return complete(ref, now)
        val reopened = updateSet(ref) { it.copy(completedAt = null) }
        return if (shownCompletedSet == ref) reopened.copy(shownCompletedSet = null, restEndsAt = null) else reopened
    }

    /**
     * Lowers the reps of the set the band shows; below zero it wraps back to the planned reps, or to
     * the reps before the first lowering when nothing was planned, so a stray tap is recoverable.
     */
    fun lowerShownReps(): ActiveWorkout {
        val ref = shownCompletedSet ?: nextSetOf(focus) ?: return this
        val set = setAt(ref) ?: return this
        val target = set.targetReps ?: set.reps ?: return this
        val current = set.reps ?: target
        return updateSet(ref) { it.copy(reps = if (current <= 0) target else current - 1, targetReps = target) }
    }

    fun focusNextExercise(): ActiveWorkout {
        if (exercises.isEmpty()) return this
        return copy(focus = (focus + 1) % exercises.size, shownCompletedSet = null)
    }

    fun focusExercise(index: Int): ActiveWorkout =
        if (index in exercises.indices) copy(focus = index, shownCompletedSet = null) else this

    fun restOver(): ActiveWorkout {
        val rested = copy(restEndsAt = null, shownCompletedSet = null)
        if (rested.nextSetOf(focus) != null) return rested
        val nextWithWork = exercises.indices
            .map { (focus + 1 + it) % exercises.size }
            .firstOrNull { rested.nextSetOf(it) != null }
            ?: return rested
        return rested.copy(focus = nextWithWork)
    }

    fun isRestDue(now: Instant): Boolean = restEndsAt?.isAfter(now) == false

    /** Rest starts when a set is ticked, so the latest tick marks the start of the current rest. */
    fun restStartedAt(): Instant? {
        if (restEndsAt == null) return null
        return exercises.flatMap { it.sets }.mapNotNull { it.completedAt }.maxOrNull() ?: lastActivityAt
    }

    fun adjustRest(delta: Duration, now: Instant): ActiveWorkout {
        val endsAt = restEndsAt ?: return this
        val adjusted = endsAt.plus(delta)
        return if (adjusted.isAfter(now)) copy(restEndsAt = adjusted) else restOver()
    }

    fun setWeight(ref: SetRef, weightKg: Double?): ActiveWorkout = updateSet(ref) { it.copy(weightKg = weightKg) }

    /** Also moves every later set that isn't done yet, the usual case when the whole 5x5 goes up. */
    fun setWeightFromHere(ref: SetRef, weightKg: Double?): ActiveWorkout {
        if (setAt(ref) == null) return this
        return updateExercise(ref.exerciseIndex) { exercise ->
            exercise.copy(
                sets = exercise.sets.mapIndexed { index, set ->
                    if (index == ref.setIndex || (index > ref.setIndex && !set.isCompleted)) set.copy(weightKg = weightKg) else set
                },
            )
        }
    }

    fun setReps(ref: SetRef, reps: Int?): ActiveWorkout = updateSet(ref) { set ->
        if (set.isCompleted) set.copy(reps = reps) else set.copy(reps = reps, targetReps = reps)
    }

    /**
     * Sets this set's reps and carries them to later open sets that were blank or matched it,
     * so picking 5 once fills a 5x5 without touching a set already planned differently.
     */
    fun setRepsFromHere(ref: SetRef, reps: Int?): ActiveWorkout {
        val before = setAt(ref) ?: return this
        val previousReps = before.reps
        return updateExercise(ref.exerciseIndex) { exercise ->
            exercise.copy(
                sets = exercise.sets.mapIndexed { index, set ->
                    val carries = index > ref.setIndex && !set.isCompleted && (set.reps == null || set.reps == previousReps)
                    when {
                        index == ref.setIndex && set.isCompleted -> set.copy(reps = reps)
                        index == ref.setIndex || carries -> set.copy(reps = reps, targetReps = reps)
                        else -> set
                    }
                },
            )
        }
    }

    fun setDuration(ref: SetRef, seconds: Int?): ActiveWorkout = updateSet(ref) { it.copy(durationSeconds = seconds) }

    fun setType(ref: SetRef, type: SetType): ActiveWorkout = updateSet(ref) { it.copy(type = type) }

    fun cycleSetType(ref: SetRef): ActiveWorkout = updateSet(ref) { set ->
        set.copy(type = SetType.entries[(set.type.ordinal + 1) % SetType.entries.size])
    }

    fun addSet(exerciseIndex: Int): ActiveWorkout {
        val exercise = exercises.getOrNull(exerciseIndex) ?: return this
        val template = exercise.sets.lastOrNull()
        val newSet = WorkoutSet(weightKg = template?.weightKg, reps = template?.targetReps ?: template?.reps)
        return updateExercise(exerciseIndex) { it.copy(sets = it.sets + newSet) }
    }

    fun removeSet(ref: SetRef): ActiveWorkout {
        if (setAt(ref) == null) return this
        return updateExercise(ref.exerciseIndex) { exercise ->
            exercise.copy(sets = exercise.sets.filterIndexed { index, _ -> index != ref.setIndex })
        }.copy(shownCompletedSet = null)
    }

    fun addExercise(exerciseId: String, sets: List<WorkoutSet>, restSeconds: Int, now: Instant): ActiveWorkout {
        val added = WorkoutExercise(nextKey(), exerciseId, restSeconds, sets = sets)
        val focusAfter = if (exercises.isEmpty()) 0 else focus
        return copy(workout = workout.copy(exercises = exercises + added), focus = focusAfter, lastActivityAt = now)
    }

    /**
     * Swaps an exercise, e.g. overhead press when the bench is taken. Completed sets stay with the
     * original exercise, so history remains true to what was lifted.
     */
    fun replaceExercise(index: Int, exerciseId: String, sets: List<WorkoutSet>): ActiveWorkout {
        val current = exercises.getOrNull(index) ?: return this
        val done = current.sets.filter { it.isCompleted }
        val replacement = current.copy(key = nextKey(), exerciseId = exerciseId, sets = sets, notes = "")
        val updated = if (done.isEmpty()) {
            exercises.toMutableList().apply { set(index, replacement) }
        } else {
            exercises.toMutableList().apply {
                set(index, current.copy(sets = done))
                add(index + 1, replacement)
            }
        }
        val newIndex = if (done.isEmpty()) index else index + 1
        return copy(workout = workout.copy(exercises = updated), focus = newIndex, shownCompletedSet = null)
    }

    fun removeExercise(index: Int): ActiveWorkout {
        if (index !in exercises.indices) return this
        val remaining = exercises.filterIndexed { i, _ -> i != index }
        val newFocus = when {
            remaining.isEmpty() -> 0
            focus > index -> focus - 1
            focus >= remaining.size -> remaining.lastIndex
            else -> focus
        }
        return copy(workout = workout.copy(exercises = remaining), focus = newFocus, shownCompletedSet = null)
    }

    fun moveExercise(from: Int, to: Int): ActiveWorkout {
        if (from !in exercises.indices || to !in exercises.indices || from == to) return this
        val focusedKey = exercises.getOrNull(focus)?.key
        val reordered = exercises.toMutableList().apply { add(to, removeAt(from)) }
        val newFocus = reordered.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)
        return copy(workout = workout.copy(exercises = reordered), focus = newFocus, shownCompletedSet = null)
    }

    fun setRestSeconds(exerciseIndex: Int, seconds: Int): ActiveWorkout =
        updateExercise(exerciseIndex) { it.copy(restSeconds = seconds.coerceAtLeast(0)) }

    fun rename(title: String): ActiveWorkout = copy(workout = workout.copy(title = title))

    fun isIdle(now: Instant, limit: Duration): Boolean = Duration.between(lastActivityAt, now) >= limit

    /** Keeps only completed sets. Returns null when nothing was lifted, so an empty workout is discarded. */
    fun finish(endedAt: Instant): Workout? {
        val lifted = exercises
            .map { exercise -> exercise.copy(sets = exercise.sets.filter { it.isCompleted }) }
            .filter { it.sets.isNotEmpty() }
        if (lifted.isEmpty()) return null
        return workout.copy(endedAt = endedAt, exercises = lifted)
    }

    private fun complete(ref: SetRef, now: Instant): ActiveWorkout {
        val rest = exercises[ref.exerciseIndex].restSeconds
        val completed = updateSet(ref) { it.copy(completedAt = now, reps = it.reps ?: it.targetReps) }
            .copy(focus = ref.exerciseIndex, shownCompletedSet = ref, lastActivityAt = now)
        return if (rest > 0) completed.copy(restEndsAt = now.plusSeconds(rest.toLong())) else completed.restOver()
    }

    private fun nextSetOf(exerciseIndex: Int): SetRef? {
        val sets = exercises.getOrNull(exerciseIndex)?.sets ?: return null
        val setIndex = sets.indexOfFirst { !it.isCompleted }
        return if (setIndex < 0) null else SetRef(exerciseIndex, setIndex)
    }

    private fun setAt(ref: SetRef): WorkoutSet? = exercises.getOrNull(ref.exerciseIndex)?.sets?.getOrNull(ref.setIndex)

    private fun updateSet(ref: SetRef, change: (WorkoutSet) -> WorkoutSet): ActiveWorkout {
        if (setAt(ref) == null) return this
        return updateExercise(ref.exerciseIndex) { exercise ->
            exercise.copy(sets = exercise.sets.mapIndexed { index, set -> if (index == ref.setIndex) change(set) else set })
        }
    }

    private fun updateExercise(index: Int, change: (WorkoutExercise) -> WorkoutExercise): ActiveWorkout {
        if (index !in exercises.indices) return this
        return copy(workout = workout.copy(exercises = exercises.mapIndexed { i, e -> if (i == index) change(e) else e }))
    }

    private fun nextKey(): Long = (exercises.maxOfOrNull { it.key } ?: 0) + 1
}
