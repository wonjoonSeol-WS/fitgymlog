package io.fitgymlog.app.workout

import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWorkoutTest {
    private val start = Instant.parse("2026-10-19T10:38:00Z")

    private fun sets(count: Int, kg: Double, reps: Int) = List(count) { WorkoutSet(weightKg = kg, reps = reps) }

    private fun workout(): ActiveWorkout = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
        .addExercise("squat_barbell", sets(3, 60.0, 8), restSeconds = 120, now = start)
        .addExercise("overhead_press_barbell", sets(2, 30.0, 5), restSeconds = 90, now = start)

    private fun threeExercises(): ActiveWorkout = workout().addExercise("bench_press_barbell", sets(1, 40.0, 5), restSeconds = 60, now = start)

    private fun ActiveWorkout.focusedId(): String? = exercises.getOrNull(focus)?.exerciseId

    private fun ActiveWorkout.completeAll(exerciseIndex: Int): ActiveWorkout =
        exercises[exerciseIndex].sets.indices.fold(this) { current, setIndex -> current.toggleSet(SetRef(exerciseIndex, setIndex), start) }

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

    @Test
    fun `completing a set keeps reps lowered before it, not its target`() {
        val done = workout().lowerShownReps().completeFocusedSet(start)

        assertEquals(7, done.exercises[0].sets[0].reps)
        assertEquals(8, done.exercises[0].sets[0].targetReps)
    }

    @Test
    fun `lowering outside rest after a rest has ended edits the next open set`() {
        val lowered = workout().completeFocusedSet(start).restOver().lowerShownReps()

        assertEquals(listOf(8, 7, 8), lowered.exercises[0].sets.map { it.reps })
    }

    @Test
    fun `lowering below zero wraps to the planned reps, not the reps before lowering`() {
        var current = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", listOf(WorkoutSet(weightKg = 60.0, reps = 2, targetReps = 8)), restSeconds = 120, now = start)
        repeat(2) { current = current.lowerShownReps() }
        assertEquals(0, current.exercises[0].sets[0].reps)

        assertEquals(8, current.lowerShownReps().exercises[0].sets[0].reps)
    }

    @Test
    fun `lowering a set that had no planned reps wraps back to the reps it had`() {
        var current = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", listOf(WorkoutSet(weightKg = 60.0)), restSeconds = 120, now = start)
            .completeFocusedSet(start)
            .setReps(SetRef(0, 0), 2)
        repeat(2) { current = current.lowerShownReps() }
        assertEquals(0, current.exercises[0].sets[0].reps)

        assertEquals(2, current.lowerShownReps().exercises[0].sets[0].reps)
    }

    @Test
    fun `rest uses the completed exercise's rest, not the focused one's`() {
        val done = workout().toggleSet(SetRef(1, 0), start)

        assertEquals(start.plusSeconds(90), done.restEndsAt)
        assertEquals(1, done.focus)
    }

    @Test
    fun `completing the set the screen showed does nothing once the band already completed it`() {
        val byBand = workout().completeFocusedSet(start)

        val byScreen = byBand.completeFocusedSet(start.plusSeconds(1), expected = SetRef(0, 0))

        assertEquals(byBand, byScreen)
        assertFalse(byScreen.exercises[0].sets[1].isCompleted)
    }

    @Test
    fun `completing an expected set completes that set even after focus moved`() {
        val done = workout().focusExercise(1).completeFocusedSet(start, expected = SetRef(0, 0))

        assertTrue(done.exercises[0].sets[0].isCompleted)
        assertFalse(done.exercises[1].sets[0].isCompleted)
        assertEquals(start.plusSeconds(120), done.restEndsAt)
    }

    @Test
    fun `rest is due once its end has passed, and an extended rest is not`() {
        val resting = workout().completeFocusedSet(start)
        val extended = resting.adjustRest(Duration.ofSeconds(15), start.plusSeconds(100))

        assertFalse(resting.isRestDue(start.plusSeconds(119)))
        assertTrue(resting.isRestDue(start.plusSeconds(120)))
        assertFalse(extended.isRestDue(start.plusSeconds(121)))
        assertFalse(workout().isRestDue(start.plusSeconds(600)))
    }

    @Test
    fun `next exercise steps forward through every exercise and wraps`() {
        val focuses = generateSequence(threeExercises()) { it.focusNextExercise() }.drop(1).take(3).map { it.focus }.toList()

        assertEquals(listOf(1, 2, 0), focuses)
    }

    @Test
    fun `rest ending skips a fully done exercise and wraps to the first with work`() {
        val doneMiddle = threeExercises().completeAll(1).restOver()
        val afterSquat = doneMiddle.completeAll(0).restOver()
        assertEquals("bench_press_barbell", afterSquat.focusedId())

        val doneFirst = threeExercises().completeAll(0).restOver()
        val afterBench = doneFirst.completeAll(2).restOver()
        assertEquals("overhead_press_barbell", afterBench.focusedId())
    }

    @Test
    fun `swapping keeps only the completed sets on the old exercise and gives the new one the new sets`() {
        val replacementSets = sets(3, 40.0, 8)

        val swapped = workout().completeFocusedSet(start).restOver().replaceExercise(0, "bench_press_barbell", replacementSets)

        assertEquals(listOf(true), swapped.exercises[0].sets.map { it.isCompleted })
        assertEquals(start, swapped.exercises[0].sets[0].completedAt)
        assertEquals(replacementSets, swapped.exercises[1].sets)
    }

    @Test
    fun `moving another exercise keeps focus on the same exercise`() {
        val moved = threeExercises().focusExercise(1).moveExercise(from = 0, to = 2)

        assertEquals(listOf("overhead_press_barbell", "bench_press_barbell", "squat_barbell"), moved.exercises.map { it.exerciseId })
        assertEquals("overhead_press_barbell", moved.focusedId())
    }

    @Test
    fun `changing weight from a set leaves earlier open sets and later done sets alone`() {
        val mixed = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", sets(4, 60.0, 5), restSeconds = 120, now = start)
            .toggleSet(SetRef(0, 2), start)

        val changed = mixed.setWeightFromHere(SetRef(0, 1), 70.0)

        assertEquals(listOf(60.0, 70.0, 60.0, 70.0), changed.exercises[0].sets.map { it.weightKg })
    }

    @Test
    fun `removing an exercise keeps focus on the same exercise or the one that takes its place`() {
        val three = threeExercises()
        val four = three.addExercise("deadlift_barbell", sets(1, 100.0, 5), restSeconds = 180, now = start)

        assertEquals("bench_press_barbell", four.focusExercise(2).removeExercise(0).focusedId())
        assertEquals("squat_barbell", three.focusExercise(0).removeExercise(2).focusedId())
        assertEquals("bench_press_barbell", three.focusExercise(1).removeExercise(1).focusedId())
        assertEquals("overhead_press_barbell", three.focusExercise(2).removeExercise(2).focusedId())
    }

    @Test
    fun `removing the only exercise leaves nothing to show`() {
        val single = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", sets(1, 60.0, 5), restSeconds = 120, now = start)

        val emptied = single.removeExercise(0)

        assertEquals(0, emptied.focus)
        assertNull(emptied.bandView())
    }

    @Test
    fun `a timed set carries its seconds to the band alongside its reps`() {
        val plank = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("plank", listOf(WorkoutSet(reps = 3, durationSeconds = 45)), restSeconds = 60, now = start)

        val view = plank.bandView()!!

        assertEquals(45, view.durationSeconds)
        assertEquals(3, view.reps)
    }
    @Test
    fun `play during rest after an exercise's last set starts the next exercise`() {
        var current = workout()
        repeat(2) { current = current.completeFocusedSet(start).restOver() }
        val resting = current.completeFocusedSet(start)

        val expected = resting.nextSetToComplete()
        val after = resting.completeNextSet(start.plusSeconds(30), expected)

        assertEquals(SetRef(1, 0), expected)
        assertEquals(1, after.focus)
        assertTrue(after.exercises[1].sets[0].isCompleted)
    }

    @Test
    fun `play during rest within an exercise completes its next set only once`() {
        val resting = workout().completeFocusedSet(start)
        val expected = resting.nextSetToComplete()

        val once = resting.completeNextSet(start.plusSeconds(30), expected)
        val twice = once.completeNextSet(start.plusSeconds(31), expected)

        assertEquals(SetRef(0, 1), expected)
        assertEquals(2, once.exercises[0].sets.count { it.isCompleted })
        assertEquals(once.exercises, twice.exercises)
    }
}
