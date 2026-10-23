package io.fitgymlog.app.data

import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappersTest {
    @Test
    fun `the previous sets of a timed exercise keep their duration and target`() {
        val plank = WorkoutSetEntity(
            workoutExerciseId = 1,
            position = 0,
            type = "NORMAL",
            weightKg = null,
            reps = null,
            targetReps = 8,
            rpe = null,
            distanceMeters = null,
            durationSeconds = 75,
            completedAt = 1_000L,
        )

        val previous = plank.toPreviousSet()

        assertEquals(75, previous.durationSeconds)
        assertEquals(8, previous.targetReps)
        assertNull(previous.completedAt)
    }

    @Test
    fun `a treadmill set keeps its speed, level and time through the database`() {
        val run = WorkoutSet(durationSeconds = 1_200, level = 4, speedKmh = 9.5, completedAt = Instant.ofEpochMilli(2_000))
        val workout = Workout(id = 7, uuid = "w-1", title = "Cardio", startedAt = Instant.ofEpochMilli(1_000), endedAt = Instant.ofEpochMilli(3_000), exercises = listOf(WorkoutExercise(key = 1, exerciseId = "treadmill", restSeconds = 0, sets = listOf(run))))
        val graph = workout.toGraph()

        val stored = WorkoutWithExercises(graph.workout, graph.exercises.map { (exercise, sets) -> ExerciseWithSets(exercise, sets) })

        assertEquals(run, stored.toWorkout().exercises.single().sets.single())
    }

    @Test
    fun `a routine's cardio plan maps to and from its database rows`() {
        val treadmill = RoutineExercise("treadmill", sets = 1, reps = null, weightKg = null, restSeconds = 0, durationSeconds = 1_200, level = 12, speedKmh = 6.5)
        val stored = RoutineWithExercises(RoutineEntity(id = 3, uuid = "r-1", updatedAt = 1, name = "Cardio", position = 0), listOf(treadmill.toEntity()))

        assertEquals(treadmill, stored.toRoutine().exercises.single())
    }
}
