package io.gymlog.app.workout

import android.content.Context
import io.gymlog.app.R
import io.gymlog.app.data.ExerciseRepository
import io.gymlog.app.data.SettingsRepository
import io.gymlog.app.data.WorkoutRepository
import io.gymlog.app.health.HealthConnectWriter
import io.gymlog.app.model.Routine
import io.gymlog.app.model.Workout
import io.gymlog.app.model.WorkoutExercise
import io.gymlog.app.model.WorkoutSet
import io.gymlog.app.ui.name
import java.time.Instant

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
                    WorkoutSet(weightKg = planned.weightKg ?: last?.weightKg, reps = planned.reps ?: last?.reps)
                },
            )
        }
        return workouts.start(routine.name, routine, planned, Instant.now()).also { WorkoutService.start(context) }
    }

    suspend fun addExercise(exerciseId: String) {
        val sets = prefilledSets(exerciseId)
        val rest = settings.settings.value.defaultRestSeconds
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
        workouts.delete(id)
        runCatching { health.delete(id) }
    }

    /** Same sets as last time this exercise was done, or three empty sets the first time. */
    private suspend fun prefilledSets(exerciseId: String): List<WorkoutSet> {
        val previous = workouts.previousSets(exerciseId)
        if (previous.isEmpty()) return List(DEFAULT_SETS) { WorkoutSet() }
        return previous.map { WorkoutSet(type = it.type, weightKg = it.weightKg, reps = it.reps) }
    }

    private suspend fun writeToHealth(workout: Workout) {
        val current = settings.settings.value
        if (!current.healthConnectEnabled || !health.hasPermissions()) return
        runCatching {
            health.write(workout, current.healthSessionType, exercises::find) { it.name(context) }
        }
    }

    private companion object {
        const val DEFAULT_SETS = 3
    }
}
