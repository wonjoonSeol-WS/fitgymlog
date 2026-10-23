package io.fitgymlog.app.workout

import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import java.time.Duration
import java.time.Instant

data class SetRef(val exerciseIndex: Int, val setIndex: Int)

data class RestClock(val left: Duration, val length: Duration) {
    val elapsed: Duration get() = (length - left).coerceAtLeast(Duration.ZERO)
}

/** What the band shows: the set just completed while resting, otherwise the next set of the focused exercise. */
data class BandView(
    val exerciseId: String,
    val setNumber: Int,
    val setCount: Int,
    val weightKg: Double?,
    val reps: Int?,
    val durationSeconds: Int?,
    val level: Int?,
    val speedKmh: Double?,
    val distanceMeters: Double?,
    val isCompleted: Boolean,
    val isExerciseDone: Boolean,
)

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
                level = null,
                speedKmh = null,
                distanceMeters = null,
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
            level = set.level,
            speedKmh = set.speedKmh,
            distanceMeters = set.distanceMeters,
            isCompleted = set.isCompleted,
            isExerciseDone = false,
        )
    }

    fun focusedNextSet(): SetRef? = nextSetOf(focus)

    /** What play on the band completes: during rest the rest ends first, so it reaches the next exercise too. */
    fun nextSetToComplete(): SetRef? = (if (restEndsAt != null) restOver() else this).focusedNextSet()

    fun completeFocusedSet(now: Instant): ActiveWorkout = nextSetOf(focus)?.let { complete(it, now) } ?: this

    /** Does nothing for a set that is already done, so the screen and the band ticking it together count once. */
    fun complete(ref: SetRef, now: Instant): ActiveWorkout {
        if (setAt(ref)?.isCompleted != false) return this
        val rest = exercises[ref.exerciseIndex].restSeconds
        val completed = updateSet(ref) { it.copy(completedAt = now, reps = it.reps ?: it.targetReps) }
            .copy(focus = ref.exerciseIndex, shownCompletedSet = ref, lastActivityAt = now)
        return if (rest > 0) completed.copy(restEndsAt = now.plusSeconds(rest.toLong())) else completed.restOver()
    }

    fun reopen(ref: SetRef): ActiveWorkout {
        if (setAt(ref)?.isCompleted != true) return this
        val reopened = updateSet(ref) { it.copy(completedAt = null) }
        return if (shownCompletedSet == ref) reopened.copy(shownCompletedSet = null, restEndsAt = null) else reopened
    }

    fun toggleSet(ref: SetRef, now: Instant): ActiveWorkout =
        if (setAt(ref)?.isCompleted == true) reopen(ref) else complete(ref, now)

    /** Below zero it wraps back to the planned reps, or to the reps before lowering when nothing was planned. */
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

    fun restOver(): ActiveWorkout = copy(restEndsAt = null, shownCompletedSet = null).focusedOnWork()

    private fun focusedOnWork(): ActiveWorkout {
        if (nextSetOf(focus) != null) return this
        val nextWithWork = exercises.indices
            .map { (focus + 1 + it) % exercises.size }
            .firstOrNull { nextSetOf(it) != null }
            ?: return this
        return copy(focus = nextWithWork)
    }

    fun isRestDue(now: Instant): Boolean = restEndsAt?.isAfter(now) == false

    /** Rest starts when a set is ticked, so the latest tick marks the start of the current rest. */
    fun restClock(now: Instant): RestClock? {
        val endsAt = restEndsAt ?: return null
        return RestClock(
            left = Duration.between(now, endsAt).coerceAtLeast(Duration.ZERO),
            length = Duration.between(lastCompletedAt() ?: lastActivityAt, endsAt).coerceAtLeast(Duration.ZERO),
        )
    }

    fun lastCompletedAt(): Instant? {
        var latest: Instant? = null
        for (exercise in exercises) for (set in exercise.sets) {
            val at = set.completedAt ?: continue
            if (latest == null || at.isAfter(latest)) latest = at
        }
        return latest
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

    /** Also fills later open sets that were blank or matched this one, so picking 5 once fills a 5x5. */
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

    fun setLevel(ref: SetRef, level: Int?): ActiveWorkout = updateSet(ref) { it.copy(level = level) }

    fun setSpeed(ref: SetRef, kmh: Double?): ActiveWorkout = updateSet(ref) { it.copy(speedKmh = kmh) }

    fun setDistance(ref: SetRef, meters: Double?): ActiveWorkout = updateSet(ref) { it.copy(distanceMeters = meters) }

    /** Changes only open working sets, in the first block of [exerciseId] that still has any. */
    fun applyTarget(exerciseId: String, weightKg: Double?, reps: Int?, durationSeconds: Int? = null): ActiveWorkout {
        val index = blockOf(exerciseId) ?: return this
        return updateExercise(index) { exercise ->
            exercise.copy(
                sets = exercise.sets.map { set ->
                    if (set.isCompleted || set.type != SetType.NORMAL) {
                        set
                    } else {
                        set.copy(
                            weightKg = weightKg ?: set.weightKg,
                            reps = reps ?: set.reps,
                            targetReps = reps ?: set.targetReps,
                            durationSeconds = durationSeconds ?: set.durationSeconds,
                        )
                    }
                },
            )
        }
    }

    /** Sets already done are never dropped, so the count can stay above [count]. */
    fun withWorkingSets(exerciseId: String, count: Int): ActiveWorkout {
        val index = blockOf(exerciseId) ?: return this
        val sets = exercises[index].sets
        val working = sets.count { it.type == SetType.NORMAL }
        if (working < count) {
            val last = sets.indexOfLast { it.type == SetType.NORMAL }
            val at = if (last >= 0) last + 1 else sets.size
            val copy = sets.getOrNull(last).nextLikeThis()
            val grown = sets.toMutableList().apply { addAll(at, List(count - working) { copy }) }
            return updateExercise(index) { it.copy(sets = grown) }.shiftedShown(index, at, count - working)
        }
        val dropped = sets.indices.reversed().filter { sets[it].type == SetType.NORMAL && !sets[it].isCompleted }.take(working - count).toSet()
        if (dropped.isEmpty()) return this
        val shown = shownCompletedSet?.let { ref -> if (ref.exerciseIndex == index) ref.copy(setIndex = ref.setIndex - dropped.count { it < ref.setIndex }) else ref }
        return updateExercise(index) { it.copy(sets = sets.filterIndexed { i, _ -> i !in dropped }) }.copy(shownCompletedSet = shown).focusedOnWork()
    }

    /** Sets already done stay, so what was lifted is still logged. */
    fun withoutOpenSets(exerciseId: String): ActiveWorkout {
        val index = blockOf(exerciseId) ?: return this
        val sets = exercises[index].sets
        if (sets.all { it.isCompleted }) return this
        if (sets.none { it.isCompleted }) return removeExercise(index).focusedOnWork()
        val trimmed = updateExercise(index) { it.copy(sets = sets.filter { set -> set.isCompleted }) }
        return trimmed.copy(shownCompletedSet = shownCompletedSet?.takeIf { it.exerciseIndex != index }).focusedOnWork()
    }

    fun setType(ref: SetRef, type: SetType): ActiveWorkout = updateSet(ref) { it.copy(type = type) }

    fun cycleSetType(ref: SetRef): ActiveWorkout = updateSet(ref) { set ->
        set.copy(type = SetType.entries[(set.type.ordinal + 1) % SetType.entries.size])
    }

    fun addSet(exerciseIndex: Int): ActiveWorkout {
        val exercise = exercises.getOrNull(exerciseIndex) ?: return this
        val newSet = exercise.sets.lastOrNull().nextLikeThis()
        return updateExercise(exerciseIndex) { it.copy(sets = it.sets + newSet) }
    }

    /** After any warm-ups already there, before the working sets. */
    fun addWarmup(exerciseIndex: Int, weightKg: Double?): ActiveWorkout {
        val exercise = exercises.getOrNull(exerciseIndex) ?: return this
        val at = exercise.sets.indexOfFirst { it.type != SetType.WARMUP }.takeIf { it >= 0 } ?: exercise.sets.size
        val sets = exercise.sets.toMutableList().apply { add(at, warmup(weightKg)) }
        return updateExercise(exerciseIndex) { it.copy(sets = sets) }.shiftedShown(exerciseIndex, at, 1)
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

    /** Completed sets stay with the original exercise, so history shows what was actually lifted. */
    fun replaceExercise(index: Int, exerciseId: String, sets: List<WorkoutSet>, restSeconds: Int): ActiveWorkout {
        val current = exercises.getOrNull(index) ?: return this
        val done = current.sets.filter { it.isCompleted }
        val replacement = current.copy(key = nextKey(), exerciseId = exerciseId, sets = sets, notes = "", restSeconds = restSeconds)
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

    fun withRest(exerciseId: String, seconds: Int): ActiveWorkout = blockOf(exerciseId)?.let { setRestSeconds(it, seconds) } ?: this

    fun restSecondsOf(exerciseId: String): Int? = blockOf(exerciseId)?.let { exercises[it].restSeconds }

    fun rename(title: String): ActiveWorkout = copy(workout = workout.copy(title = title))

    /** A set that takes time, such as 45 minutes on the bike, isn't idleness, so the next one's duration adds to the limit. */
    fun isIdle(now: Instant, limit: Duration): Boolean {
        val running = focusedNextSet()?.let(::setAt)?.durationSeconds?.toLong() ?: 0
        return Duration.between(lastActivityAt, now) >= limit.plusSeconds(running)
    }

    /** Keeps only completed sets. Returns null when nothing was lifted, so an empty workout is discarded. */
    fun finish(endedAt: Instant): Workout? {
        val lifted = exercises
            .map { exercise -> exercise.copy(sets = exercise.sets.filter { it.isCompleted }) }
            .filter { it.sets.isNotEmpty() }
        if (lifted.isEmpty()) return null
        return workout.copy(endedAt = endedAt, exercises = lifted)
    }

    private fun nextSetOf(exerciseIndex: Int): SetRef? {
        val sets = exercises.getOrNull(exerciseIndex)?.sets ?: return null
        val setIndex = sets.indexOfFirst { !it.isCompleted }
        return if (setIndex < 0) null else SetRef(exerciseIndex, setIndex)
    }

    private fun blockOf(exerciseId: String): Int? {
        val blocks = exercises.indices.filter { exercises[it].exerciseId == exerciseId }
        return blocks.firstOrNull { index -> exercises[index].sets.any { !it.isCompleted } } ?: blocks.firstOrNull()
    }

    private fun shiftedShown(exerciseIndex: Int, at: Int, by: Int): ActiveWorkout {
        val shown = shownCompletedSet ?: return this
        return if (shown.exerciseIndex == exerciseIndex && shown.setIndex >= at) copy(shownCompletedSet = shown.copy(setIndex = shown.setIndex + by)) else this
    }

    fun setAt(ref: SetRef): WorkoutSet? = exercises.getOrNull(ref.exerciseIndex)?.sets?.getOrNull(ref.setIndex)

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

private fun WorkoutSet?.nextLikeThis() = WorkoutSet(
    weightKg = this?.weightKg,
    reps = this?.targetReps ?: this?.reps,
    durationSeconds = this?.durationSeconds,
    level = this?.level,
    speedKmh = this?.speedKmh,
    distanceMeters = this?.distanceMeters,
)
