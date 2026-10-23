package io.fitgymlog.app.coach

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.SetRef
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutChangesTest {
    private val start = Instant.parse("2026-10-23T10:00:00Z")

    private fun sets(count: Int, kg: Double, reps: Int) = List(count) { WorkoutSet(weightKg = kg, reps = reps) }

    private val active = ActiveWorkout(Workout(uuid = "today", title = "Day A", startedAt = start, exercises = emptyList()))
        .addExercise("squat_barbell", sets(3, 60.0, 5), restSeconds = 120, now = start)
        .addExercise("overhead_press_barbell", sets(3, 30.0, 5), restSeconds = 90, now = start)
        .toggleSet(SetRef(0, 0), start)
        .toggleSet(SetRef(1, 0), start)

    @Test
    fun `a change applies only to its own workout, once, to an exercise there, or for an add to one not there yet`() {
        assertTrue(active.accepts(Change("squat_barbell", workoutUuid = "today")))
        assertTrue(active.accepts(Change("bench_press_barbell", workoutUuid = "today", action = ChangeAction.ADD)))
        assertFalse(active.accepts(Change("squat_barbell", workoutUuid = "yesterday")))
        assertFalse(active.accepts(Change("squat_barbell", workoutUuid = "today", applied = true)))
        assertFalse(active.accepts(Change("bench_press_barbell", workoutUuid = "today")))
        assertFalse(active.accepts(Change("bench_press_barbell", workoutUuid = "today", action = ChangeAction.REMOVE)))
        assertFalse(active.accepts(Change("squat_barbell", workoutUuid = "today", action = ChangeAction.ADD)))
    }

    private fun change(exerciseId: String, action: ChangeAction = ChangeAction.ADJUST, weightKg: Double? = null, reps: Int? = null, sets: Int? = null, minutes: Int? = null, restSeconds: Int? = null) =
        Change(exerciseId, weightKg = weightKg, reps = reps, workoutUuid = "today", action = action, sets = sets, minutes = minutes, restSeconds = restSeconds)

    @Test
    fun `changes apply together, leaving sets already done as they were lifted, and report which ones applied`() {
        val chosen = mapOf(
            1 to change("squat_barbell", weightKg = 55.0, reps = 3, sets = 5),
            3 to change("overhead_press_barbell", ChangeAction.REMOVE),
            4 to change("bench_press_barbell", ChangeAction.ADD, sets = 4, reps = 8),
            6 to change("hip_thrust_barbell", ChangeAction.ADD),
            7 to change("squat_barbell").copy(workoutUuid = "yesterday"),
        )
        val bench = NewBlock(sets(2, 40.0, 8), restSeconds = 75)

        val applied = active.applying(chosen, mapOf("bench_press_barbell" to bench), start)
        val changed = applied.workout

        assertEquals(setOf(1, 3, 4), applied.indices)
        assertEquals(listOf("squat_barbell", "overhead_press_barbell", "bench_press_barbell"), changed.exercises.map { it.exerciseId })
        assertEquals(listOf(60.0, 55.0, 55.0, 55.0, 55.0), changed.exercises[0].sets.map { it.weightKg })
        assertEquals(listOf(5, 3, 3, 3, 3), changed.exercises[0].sets.map { it.reps })
        assertEquals(listOf(30.0), changed.exercises[1].sets.map { it.weightKg })
        assertEquals(bench.sets, changed.exercises[2].sets)
        assertEquals(75, changed.exercises[2].restSeconds)
    }

    @Test
    fun `two adds of one exercise add it once`() {
        val bench = NewBlock(sets(2, 40.0, 8), restSeconds = 75)

        val applied = active.applying(mapOf(2 to change("bench_press_barbell", ChangeAction.ADD), 5 to change("bench_press_barbell", ChangeAction.ADD)), mapOf("bench_press_barbell" to bench), start)

        assertEquals(setOf(2), applied.indices)
        assertEquals(1, applied.workout.exercises.count { it.exerciseId == "bench_press_barbell" })
    }

    @Test
    fun `fewer sets drop only open ones, and a new target skips the sets already done`() {
        val applied = active.applying(mapOf(0 to change("squat_barbell", weightKg = 65.0, sets = 2)), emptyMap(), start)

        assertEquals(listOf(60.0, 65.0), applied.workout.exercises[0].sets.map { it.weightKg })
        assertTrue(applied.workout.exercises[0].sets[0].isCompleted)
    }

    @Test
    fun `sets added after every set is done take the new target`() {
        val finished = active.toggleSet(SetRef(0, 1), start).toggleSet(SetRef(0, 2), start)

        val sets = finished.applying(mapOf(0 to change("squat_barbell", weightKg = 65.0, reps = 3, sets = 5)), emptyMap(), start).workout.exercises[0].sets

        assertEquals(listOf(60.0, 60.0, 60.0, 65.0, 65.0), sets.map { it.weightKg })
        assertEquals(listOf(5, 5, 5, 3, 3), sets.map { it.reps })
    }

    @Test
    fun `a change that leaves the workout as it was is not counted as applied`() {
        val finished = active.toggleSet(SetRef(0, 1), start).toggleSet(SetRef(0, 2), start)

        val applied = finished.applying(mapOf(0 to change("squat_barbell", ChangeAction.REMOVE), 1 to change("squat_barbell", weightKg = 70.0), 2 to change("overhead_press_barbell", sets = 1)), emptyMap(), start)

        assertEquals(setOf(2), applied.indices)
        assertEquals(1, applied.workout.exercises[1].sets.size)
    }

    @Test
    fun `a new rest goes to the block with sets left, and the same rest again is not counted as applied`() {
        val twice = active.toggleSet(SetRef(0, 1), start).toggleSet(SetRef(0, 2), start)
            .addExercise("squat_barbell", sets(2, 50.0, 8), restSeconds = 90, now = start)

        val applied = twice.applying(mapOf(0 to change("squat_barbell", restSeconds = 180), 1 to change("overhead_press_barbell", restSeconds = 90)), emptyMap(), start)

        assertEquals(setOf(0), applied.indices)
        assertEquals(listOf(120, 90, 180), applied.workout.exercises.map { it.restSeconds })
        assertEquals(90, twice.restSecondsOf("squat_barbell"))
    }

    @Test
    fun `an added exercise rests as the coach says, or as the settings say for its kind`() {
        assertEquals(60, restToAdd(change("bench_press_barbell", ChangeAction.ADD, restSeconds = 60), ExerciseKind.WEIGHT_REPS, defaultRestSeconds = 120))
        assertEquals(120, restToAdd(change("bench_press_barbell", ChangeAction.ADD), ExerciseKind.WEIGHT_REPS, defaultRestSeconds = 120))
        assertEquals(0, restToAdd(change("treadmill", ChangeAction.ADD), ExerciseKind.CARDIO, defaultRestSeconds = 120))
    }

    @Test
    fun `cardio minutes become the open sets' seconds`() {
        val riding = active.addExercise("treadmill", listOf(WorkoutSet(durationSeconds = 600, level = 5)), restSeconds = 0, now = start)

        val changed = riding.applying(mapOf(0 to change("treadmill", minutes = 25)), emptyMap(), start).workout

        assertEquals(listOf(1500), changed.exercises[2].sets.map { it.durationSeconds })
        assertEquals(listOf(5), changed.exercises[2].sets.map { it.level })
    }

    @Test
    fun `an added exercise gets the coach's sets and reps, or as many as last session's working sets, with weights from those sets only`() {
        val warmup = WorkoutSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10)
        val previous = listOf(warmup, WorkoutSet(weightKg = 60.0, reps = 5), WorkoutSet(weightKg = 62.5, reps = 3))

        val planned = setsToAdd(Change("squat_barbell", sets = 3, reps = 8, action = ChangeAction.ADD), ExerciseKind.WEIGHT_REPS, previous)
        val asBefore = setsToAdd(Change("squat_barbell", action = ChangeAction.ADD), ExerciseKind.WEIGHT_REPS, previous)
        val firstTime = setsToAdd(Change("leg_press", action = ChangeAction.ADD), ExerciseKind.WEIGHT_REPS, emptyList())
        val ride = setsToAdd(Change("treadmill", minutes = 30, action = ChangeAction.ADD), ExerciseKind.CARDIO, emptyList())

        assertEquals(listOf(60.0, 62.5, 62.5), planned.map { it.weightKg })
        assertEquals(listOf(8, 8, 8), planned.map { it.reps })
        assertEquals(listOf(60.0, 62.5), asBefore.map { it.weightKg })
        assertEquals(listOf(5, 3), asBefore.map { it.reps })
        assertEquals(3, firstTime.size)
        assertEquals(listOf(1800), ride.map { it.durationSeconds })
    }
}
