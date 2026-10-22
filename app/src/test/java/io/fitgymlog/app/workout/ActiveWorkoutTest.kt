package io.fitgymlog.app.workout

import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWorkoutTest {
    private val start = Instant.parse("2026-10-19T10:38:00Z")

    private fun sets(count: Int, kg: Double, reps: Int) = List(count) { WorkoutSet(weightKg = kg, reps = reps) }

    private fun workout(): ActiveWorkout = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
        .addExercise("squat_barbell", sets(3, 60.0, 8), restSeconds = 120, now = start)
        .addExercise("overhead_press_barbell", sets(2, 30.0, 5), restSeconds = 90, now = start)

    @Test
    fun `completing a set starts that exercise's rest and shows the completed set`() {
        val done = workout().completeFocusedSet(start.plusSeconds(60))

        assertEquals(start.plusSeconds(180), done.restEndsAt)
        assertEquals(SetRef(0, 0), done.shownCompletedSet)
        assertTrue(done.bandView()!!.isCompleted)
    }

    @Test
    fun `lowering reps during rest edits the completed set, not the next one`() {
        val lowered = workout().completeFocusedSet(start).lowerShownReps()

        assertEquals(7, lowered.exercises[0].sets[0].reps)
        assertEquals(8, lowered.exercises[0].sets[1].reps)
    }

    @Test
    fun `lowering reps outside rest edits the next set and keeps its target`() {
        val lowered = workout().lowerShownReps().lowerShownReps()

        assertEquals(6, lowered.exercises[0].sets[0].reps)
        assertEquals(8, lowered.exercises[0].sets[0].targetReps)
    }

    @Test
    fun `lowering below zero wraps back to the planned reps`() {
        var current = workout().completeFocusedSet(start)
        repeat(8) { current = current.lowerShownReps() }
        assertEquals(0, current.exercises[0].sets[0].reps)

        assertEquals(8, current.lowerShownReps().exercises[0].sets[0].reps)
    }

    @Test
    fun `after the last set of an exercise, rest ending moves focus to the next exercise with work`() {
        var current = workout()
        repeat(3) { current = current.completeFocusedSet(start).restOver() }

        assertEquals(1, current.focus)
        assertEquals("overhead_press_barbell", current.bandView()!!.exerciseId)
    }

    @Test
    fun `next exercise wraps around and drops the shown completed set`() {
        val rotated = workout().completeFocusedSet(start).focusNextExercise().focusNextExercise()

        assertEquals(0, rotated.focus)
        assertNull(rotated.shownCompletedSet)
        assertEquals(2, rotated.bandView()!!.setNumber)
    }

    @Test
    fun `swapping an exercise with completed sets keeps them and inserts the new exercise after it`() {
        val swapped = workout().completeFocusedSet(start).restOver()
            .replaceExercise(0, "bench_press_barbell", sets(3, 40.0, 8))

        assertEquals(listOf("squat_barbell", "bench_press_barbell", "overhead_press_barbell"), swapped.exercises.map { it.exerciseId })
        assertEquals(1, swapped.exercises[0].sets.size)
        assertEquals(1, swapped.focus)
    }

    @Test
    fun `swapping an untouched exercise replaces it in place`() {
        val swapped = workout().replaceExercise(1, "bench_press_barbell", sets(3, 40.0, 8))

        assertEquals(listOf("squat_barbell", "bench_press_barbell"), swapped.exercises.map { it.exerciseId })
    }

    @Test
    fun `moving exercises keeps focus on the same exercise`() {
        val moved = workout().focusExercise(1).moveExercise(from = 1, to = 0)

        assertEquals(0, moved.focus)
        assertEquals("overhead_press_barbell", moved.bandView()!!.exerciseId)
    }

    @Test
    fun `finishing keeps only completed sets and drops untouched exercises`() {
        val finished = workout().completeFocusedSet(start.plusSeconds(30)).finish(start.plusSeconds(600))!!

        assertEquals(1, finished.exercises.size)
        assertEquals(1, finished.exercises[0].sets.size)
        assertEquals(start.plusSeconds(600), finished.endedAt)
    }

    @Test
    fun `finishing without any completed set discards the workout`() {
        assertNull(workout().finish(start.plusSeconds(600)))
    }

    @Test
    fun `shortening rest past now ends it`() {
        val resting = workout().completeFocusedSet(start)

        assertNull(resting.adjustRest(Duration.ofSeconds(-200), start.plusSeconds(10)).restEndsAt)
        assertEquals(start.plusSeconds(135), resting.adjustRest(Duration.ofSeconds(15), start).restEndsAt)
    }

    @Test
    fun `changing weight from a set moves later open sets but not finished or earlier ones`() {
        val done = workout().completeFocusedSet(start).restOver()
        val warm = done.setWeight(SetRef(0, 2), 50.0)

        val changed = warm.setWeightFromHere(SetRef(0, 1), 65.0)

        assertEquals(listOf(60.0, 65.0, 65.0), changed.exercises[0].sets.map { it.weightKg })
        assertEquals(listOf(30.0, 30.0), changed.exercises[1].sets.map { it.weightKg })
    }

    @Test
    fun `changing weight from a set skips later sets already done`() {
        val mixed = workout().toggleSet(SetRef(0, 2), start)

        val changed = mixed.setWeightFromHere(SetRef(0, 0), 70.0)

        assertEquals(listOf(70.0, 70.0, 60.0), changed.exercises[0].sets.map { it.weightKg })
    }

    @Test
    fun `picking reps fills later blank or matching sets but keeps sets planned differently`() {
        val planned = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", listOf(WorkoutSet(reps = 8), WorkoutSet(reps = null), WorkoutSet(reps = 8), WorkoutSet(reps = 3)), 120, start)

        val picked = planned.setRepsFromHere(SetRef(0, 0), 5)

        assertEquals(listOf(5, 5, 5, 3), picked.exercises[0].sets.map { it.reps })
        assertEquals(5, picked.exercises[0].sets[1].targetReps)
    }

    @Test
    fun `correcting a finished set keeps its target and carries to later matching sets`() {
        val done = workout().completeFocusedSet(start)

        val corrected = done.setRepsFromHere(SetRef(0, 0), 6)

        assertEquals(listOf(6, 6, 6), corrected.exercises[0].sets.map { it.reps })
        assertEquals(8, corrected.exercises[0].sets[0].targetReps)
    }

    @Test
    fun `idle is measured from the last completed set`() {
        val done = workout().completeFocusedSet(start.plusSeconds(600))

        assertTrue(done.isIdle(start.plusSeconds(600 + 1800), Duration.ofMinutes(30)))
        assertEquals(false, done.isIdle(start.plusSeconds(600 + 1799), Duration.ofMinutes(30)))
    }
}
