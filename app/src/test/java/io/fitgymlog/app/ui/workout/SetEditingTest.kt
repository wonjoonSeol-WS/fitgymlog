package io.fitgymlog.app.ui.workout

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.SetRef
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetEditingTest {
    private val start = Instant.parse("2026-10-19T10:38:00Z")

    @Test
    fun `typed weights read with either separator`() {
        assertEquals(TypedWeight.Amount(62.5), readTypedWeight("62.5"))
        assertEquals(TypedWeight.Amount(62.5), readTypedWeight("62,5"))
        assertEquals(TypedWeight.Amount(60.0), readTypedWeight("60."))
        assertEquals(TypedWeight.Amount(0.5), readTypedWeight(".5"))
    }

    @Test
    fun `nothing typed yet or a lone separator is blank`() {
        assertEquals(TypedWeight.Blank, readTypedWeight(""))
        assertEquals(TypedWeight.Blank, readTypedWeight("."))
        assertEquals(TypedWeight.Blank, readTypedWeight(","))
    }

    @Test
    fun `text that cannot become a weight is rejected`() {
        assertEquals(TypedWeight.Rejected, readTypedWeight("12345"))
        assertEquals(TypedWeight.Rejected, readTypedWeight("1.234"))
        assertEquals(TypedWeight.Rejected, readTypedWeight("1.2.3"))
        assertEquals(TypedWeight.Rejected, readTypedWeight("-5"))
    }

    @Test
    fun `a weight is shown for editing without trailing zeros and reads back the same`() {
        assertEquals("", editableWeight(null))
        assertEquals("0", editableWeight(0.0))
        assertEquals("60", editableWeight(60.0))
        assertEquals("1.25", editableWeight(1.25))
        assertEquals(TypedWeight.Amount(62.5), readTypedWeight(editableWeight(62.5)))
    }

    @Test
    fun `row numbers count normal sets only`() {
        val types = listOf(SetType.WARMUP, SetType.WARMUP, SetType.NORMAL, SetType.NORMAL, SetType.DROP, SetType.NORMAL)

        assertEquals(listOf(null, null, 1, 2, null, 3), normalSetNumbers(types.map { WorkoutSet(type = it) }))
    }

    @Test
    fun `the amount is seconds for timed exercises and reps otherwise`() {
        val set = WorkoutSet(reps = 10, durationSeconds = 60)

        assertEquals(60, set.amount(ExerciseKind.DURATION))
        assertEquals(10, set.amount(ExerciseKind.BODYWEIGHT_REPS))
        assertEquals(10, set.amount(ExerciseKind.WEIGHT_REPS))
    }

    @Test
    fun `the open set of the focused exercise is the one a complete tap expects`() {
        val workout = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", List(3) { WorkoutSet(weightKg = 60.0, reps = 5) }, restSeconds = 0, now = start)
            .addExercise("bench_press_barbell", List(3) { WorkoutSet(weightKg = 40.0, reps = 5) }, restSeconds = 0, now = start)
            .focusExercise(1)
            .toggleSet(SetRef(1, 0), start)

        assertEquals(SetRef(1, 1), workout.focusedOpenSet())
    }

    @Test
    fun `an exercise with every set done has no open set`() {
        val workout = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("plank", listOf(WorkoutSet(durationSeconds = 60)), restSeconds = 0, now = start)
            .toggleSet(SetRef(0, 0), start)

        assertNull(workout.focusedOpenSet())
    }

    @Test
    fun `the pad offers a 25 rep set and a three minute hold`() {
        assertTrue(25 in REP_CHOICES)
        assertEquals(30, REP_CHOICES.last())
        assertTrue(180 in SECOND_CHOICES)
        assertEquals(300, SECOND_CHOICES.last())
        assertEquals(SECOND_CHOICES.sorted(), SECOND_CHOICES)
    }
}
