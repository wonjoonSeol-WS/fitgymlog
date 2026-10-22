package io.fitgymlog.app.workout

import android.content.Context
import android.util.Log
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.data.ExerciseRepository
import io.fitgymlog.app.data.SettingsRepository
import io.fitgymlog.app.data.WorkoutRepository
import io.fitgymlog.app.health.HealthConnectWriter
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.defaultRestSeconds
import io.fitgymlog.app.model.defaultSets
import io.fitgymlog.app.ui.name
import java.time.Instant
import kotlinx.coroutines.flow.first

/** Starting and ending workouts, which touch the database, the band service and Health Connect together. */
class WorkoutActions(
    private val context: Context,
    private val workouts: WorkoutRepository,
    private val exercises: ExerciseRepository,
    private val settings: SettingsRepository,
    private val health: HealthConnectWriter,
) {
    suspend fun startEmpty(): ActiveWorkout =
        workouts.start(context.getString(R.string.workout_default_title), routine = null, exercises = emptyList(), now = Instant.now())
            .also { WorkoutService.start(context) }

    suspend fun startRoutine(routine: Routine): ActiveWorkout {
        val planned = routine.exercises.mapIndexed { index, planned ->
            val previous = workouts.previousSets(planned.exerciseId)
            WorkoutExercise(
                key = index + 1L,
                exerciseId = planned.exerciseId,
                restSeconds = planned.restSeconds,
                sets = List(planned.sets) { setIndex ->
                    val last = previous.getOrNull(setIndex) ?: previous.lastOrNull()
                    WorkoutSet(
                        weightKg = planned.weightKg ?: lastKnownWeight(previous, setIndex),
                        reps = planned.reps ?: last?.reps,
                        durationSeconds = last?.durationSeconds,
                    )
                },
            )
        }
        return workouts.start(routine.name, routine, planned, Instant.now()).also { WorkoutService.start(context) }
    }

    suspend fun addExercise(exerciseId: String) {
        val sets = prefilledSets(exerciseId)
        val rest = exercises.find(exerciseId).kind.defaultRestSeconds(settings.settings.value.defaultRestSeconds)
        workouts.update { it.addExercise(exerciseId, sets, rest, Instant.now()) }
    }

    suspend fun replaceExercise(index: Int, exerciseId: String) {
        val sets = prefilledSets(exerciseId)
        workouts.update { it.replaceExercise(index, exerciseId, sets) }
    }

    suspend fun finish(endedAt: Instant = Instant.now()): Workout? {
        val finished = workouts.finish(endedAt)
        WorkoutService.stop(context)
        if (finished != null) writeToHealth(finished)
        return finished
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
    private suspend fun prefilledSets(exerciseId: String): List<WorkoutSet> {
        val previous = workouts.previousSets(exerciseId)
        if (previous.isEmpty()) return List(exercises.find(exerciseId).kind.defaultSets()) { WorkoutSet() }
        return previous.mapIndexed { index, set ->
            WorkoutSet(type = set.type, weightKg = lastKnownWeight(previous, index), reps = set.reps, durationSeconds = set.durationSeconds)
        }
    }

    /** A set logged without a weight borrows the closest earlier weight, so one missed entry doesn't blank the next session. */
    private fun lastKnownWeight(previous: List<WorkoutSet>, setIndex: Int): Double? =
        previous.take(setIndex + 1).lastOrNull { it.weightKg != null }?.weightKg ?: previous.firstOrNull { it.weightKg != null }?.weightKg

    private suspend fun writeToHealth(workout: Workout) {
        val current = settings.settings.value
        if (!current.healthConnectEnabled) return
        safely {
            if (health.hasPermissions()) health.write(workout, current.healthSessionType, exercises::find) { it.name(context) }
        }
    }

    /** Health Connect is best effort: a failure there must not undo or block the workout saved here. */
    private suspend fun safely(call: suspend () -> Unit) {
        catching { call() }.onFailure { Log.w(LOG_TAG, "Health Connect call failed", it) }
    }
}
