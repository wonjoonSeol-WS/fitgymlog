package io.fitgymlog.app.workout

import android.content.Context
import android.util.Log
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.data.ExerciseRepository
import io.fitgymlog.app.data.Settings
import io.fitgymlog.app.data.SettingsRepository
import io.fitgymlog.app.data.WorkoutRepository
import io.fitgymlog.app.health.HealthConnectWriter
import io.fitgymlog.app.model.Equipment
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.WorkoutSource
import io.fitgymlog.app.model.defaultRestSeconds
import io.fitgymlog.app.model.defaultSets
import io.fitgymlog.app.model.isTimed
import io.fitgymlog.app.ui.fromDisplayWeight
import io.fitgymlog.app.ui.name
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Starting and ending workouts, which touch the database, the band service and Health Connect together. */
class WorkoutActions(
    private val context: Context,
    private val workouts: WorkoutRepository,
    private val exercises: ExerciseRepository,
    private val settings: SettingsRepository,
    private val health: HealthConnectWriter,
) {
    /** The workout to ask "how hard was it?" about, set when it is finished on the phone and cleared once answered or skipped. */
    val effortPrompt = MutableStateFlow<Long?>(null)

    suspend fun startEmpty(): ActiveWorkout =
        workouts.start(context.getString(R.string.workout_default_title), routine = null, exercises = emptyList(), now = Instant.now())
            .also { WorkoutService.start(context) }

    suspend fun startRoutine(routine: Routine): ActiveWorkout {
        val current = settings.settings.value
        val planned = routine.exercises.mapIndexed { index, planned ->
            val exercise = exercises.find(planned.exerciseId)
            val previous = workouts.previousSets(planned.exerciseId)
            WorkoutExercise(
                key = index + 1L,
                exerciseId = planned.exerciseId,
                restSeconds = planned.restSeconds,
                sets = current.warmedUp(exercise, startingSets(planned, exercise.kind, previous), previous),
            )
        }
        return workouts.start(routine.name, routine, planned, Instant.now()).also { WorkoutService.start(context) }
    }

    /** [kind] is passed for an exercise created a moment ago, which the exercise list may not hold yet. */
    suspend fun addExercise(exerciseId: String, kind: ExerciseKind = exercises.find(exerciseId).kind) {
        val sets = settings.settings.value.warmedUp(exercises.find(exerciseId), prefilledSets(exerciseId, kind))
        val rest = kind.defaultRestSeconds(settings.settings.value.defaultRestSeconds)
        workouts.update { it.addExercise(exerciseId, sets, rest, Instant.now()) }
    }

    suspend fun replaceExercise(index: Int, exerciseId: String, kind: ExerciseKind = exercises.find(exerciseId).kind) {
        val sets = settings.settings.value.warmedUp(exercises.find(exerciseId), prefilledSets(exerciseId, kind))
        val rest = kind.defaultRestSeconds(settings.settings.value.defaultRestSeconds)
        workouts.update { it.replaceExercise(index, exerciseId, sets, rest) }
    }

    suspend fun finish(endedAt: Instant = Instant.now()): Workout? {
        val finished = workouts.finish(endedAt)
        WorkoutService.stop(context)
        if (finished != null) writeToHealth(finished)
        return finished
    }

    /** Changes the stored workout rather than the copy on screen, so an edit synced in meanwhile is kept. */
    suspend fun retime(id: Long, start: Instant, end: Instant): RetimeProblem? {
        val stored = workouts.observe(id).first()?.takeIf { it.endedAt != null } ?: return null
        stored.retimeProblem(start, end, Instant.now())?.let { return it }
        if (workouts.clashes(stored, start)) return RetimeProblem.CLASH
        rewriteHealth(stored, workouts.saveEdited(stored.retimed(start, end)))
        return null
    }

    /** How hard the workout felt, 1 to 10, or null to clear it; Health Connect's copy carries it in its notes. */
    suspend fun rateEffort(id: Long, effort: Int?) {
        val stored = workouts.observe(id).first()?.takeIf { it.endedAt != null } ?: return
        val rating = effort?.coerceIn(1, 10)
        if (stored.effort == rating) return
        rewriteHealth(stored, workouts.saveEdited(stored.copy(effort = rating)))
    }

    /** Only a session this phone wrote is replaced, so one Samsung Health or another app recorded stays theirs. */
    private suspend fun rewriteHealth(stored: Workout, edited: Workout) {
        val writtenHere = stored.source == WorkoutSource.APP && settings.settings.value.healthConnectEnabled &&
            catching { health.wrote(stored, exercises::find) }.getOrDefault(true)
        if (writtenHere) {
            safely { health.delete(stored, exercises::find) }
            writeToHealth(edited)
        }
    }

    suspend fun discard() {
        workouts.discard()
        WorkoutService.stop(context)
    }

    suspend fun deleteWorkout(id: Long) {
        val deleted = workouts.observe(id).first()
        workouts.delete(id)
        if (deleted != null) safely { health.delete(deleted, exercises::find) }
    }

    /** Same sets as last time this exercise was done, or empty sets the first time. */
    private suspend fun prefilledSets(exerciseId: String, kind: ExerciseKind): List<WorkoutSet> {
        val previous = workouts.previousSets(exerciseId)
        if (previous.isEmpty()) return List(kind.defaultSets()) { WorkoutSet() }
        return previous.mapIndexed { index, set ->
            WorkoutSet(
                type = set.type,
                weightKg = lastKnownWeight(previous, index),
                reps = set.targetReps ?: set.reps,
                durationSeconds = set.durationSeconds?.takeIf { kind.isTimed },
                level = set.level?.takeIf { kind == ExerciseKind.CARDIO },
                speedKmh = set.speedKmh?.takeIf { kind == ExerciseKind.CARDIO },
            )
        }
    }

    private suspend fun writeToHealth(workout: Workout) {
        val current = settings.settings.value
        if (!current.healthConnectEnabled) return
        safely {
            if (health.hasPermissions()) health.write(workout, current.healthSessionType, current.skipRecordedCardio, exercises::find) { it.name(context) }
        }
    }

    /** Health Connect is best effort: a failure there must not undo or block the workout saved here. */
    private suspend fun safely(call: suspend () -> Unit) {
        catching { call() }.onFailure { Log.w(LOG_TAG, "Health Connect call failed", it) }
    }
}

/** What the routine plans wins; anything it leaves open comes from the same working set last session. */
internal fun startingSets(planned: RoutineExercise, kind: ExerciseKind, previous: List<WorkoutSet>): List<WorkoutSet> {
    val working = previous.filter { it.type == SetType.NORMAL }
    return List(planned.sets) { setIndex -> startingSet(planned, kind, working, setIndex) }
}

private fun startingSet(planned: RoutineExercise, kind: ExerciseKind, working: List<WorkoutSet>, setIndex: Int): WorkoutSet {
    val last = working.getOrNull(setIndex) ?: working.lastOrNull()
    return WorkoutSet(
        weightKg = planned.weightKg ?: lastKnownWeight(working, setIndex),
        reps = if (kind.isTimed) null else planned.reps ?: last?.targetReps ?: last?.reps,
        durationSeconds = (planned.durationSeconds ?: last?.durationSeconds)?.takeIf { kind.isTimed },
        level = (planned.level ?: last?.level)?.takeIf { kind == ExerciseKind.CARDIO },
        speedKmh = (planned.speedKmh ?: last?.speedKmh)?.takeIf { kind == ExerciseKind.CARDIO },
    )
}

/** Last session's set for each row: warm-ups against its warm-ups and the rest against the rest, each in order, so an added warm-up shifts nothing. */
internal fun previousRows(sets: List<WorkoutSet>, previous: List<WorkoutSet>): List<WorkoutSet?> {
    val (warmups, rest) = previous.partition { it.type == SetType.WARMUP }
    var warmup = 0
    var other = 0
    return sets.map { if (it.type == SetType.WARMUP) warmups.getOrNull(warmup++) else rest.getOrNull(other++) }
}

val Settings.barInKg: Double get() = fromDisplayWeight(bar, unit)

/** [previous] is last session's sets, whose warm-ups come back as they were done. */
internal fun Settings.warmedUp(exercise: Exercise, sets: List<WorkoutSet>, previous: List<WorkoutSet> = emptyList()): List<WorkoutSet> =
    if (warmupSet && exercise.equipment == Equipment.BARBELL) withWarmup(sets, barInKg, previous) else sets

/**
 * Last session's warm-ups, so a warm-up changed once stays changed, and five on the empty bar only before there are any.
 * Not when the sets already hold a warm-up, such as one copied from last session, or when the work is the bar itself.
 */
internal fun withWarmup(sets: List<WorkoutSet>, barKg: Double, previous: List<WorkoutSet> = emptyList()): List<WorkoutSet> {
    if (sets.any { it.type == SetType.WARMUP }) return sets
    val working = sets.firstOrNull { it.type == SetType.NORMAL }?.weightKg
    if (working != null && working <= barKg) return sets
    val last = previous.filter { it.type == SetType.WARMUP }.map { warmup(it.weightKg, it.targetReps ?: it.reps) }
    return last.ifEmpty { listOf(warmup(barKg)) } + sets
}

internal fun warmup(weightKg: Double?, reps: Int? = WARMUP_REPS) = WorkoutSet(type = SetType.WARMUP, weightKg = weightKg, reps = reps)

private const val WARMUP_REPS = 5

/** A set logged without a weight borrows the closest earlier weight, so one missed entry doesn't blank the next session. */
private fun lastKnownWeight(previous: List<WorkoutSet>, setIndex: Int): Double? =
    previous.take(setIndex + 1).lastOrNull { it.weightKg != null }?.weightKg ?: previous.firstOrNull { it.weightKg != null }?.weightKg
