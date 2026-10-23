package io.fitgymlog.app.workout

import io.fitgymlog.app.model.SetType
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
            .replaceExercise(0, "bench_press_barbell", sets(3, 40.0, 8), restSeconds = 90)

        assertEquals(listOf("squat_barbell", "bench_press_barbell", "overhead_press_barbell"), swapped.exercises.map { it.exerciseId })
        assertEquals(1, swapped.exercises[0].sets.size)
        assertEquals(1, swapped.focus)
    }

    @Test
    fun `swapping an untouched exercise replaces it in place`() {
        val swapped = workout().replaceExercise(1, "bench_press_barbell", sets(3, 40.0, 8), restSeconds = 90)

        assertEquals(listOf("squat_barbell", "bench_press_barbell"), swapped.exercises.map { it.exerciseId })
    }

    @Test
    fun `a replacement takes the rest it was given, not the rest of the exercise it replaces`() {
        val swapped = workout().replaceExercise(0, "treadmill", listOf(WorkoutSet(durationSeconds = 300)), restSeconds = 0)

        assertEquals(listOf(0, 90), swapped.exercises.map { it.restSeconds })
    }

    @Test
    fun `an added set copies the last set's minutes and level as well as its weight and reps`() {
        val cardio = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("treadmill", listOf(WorkoutSet(durationSeconds = 300, level = 8, speedKmh = 7.0)), restSeconds = 0, now = start)
            .addSet(0)

        assertEquals(WorkoutSet(durationSeconds = 300, level = 8, speedKmh = 7.0), cardio.exercises[0].sets[1])
    }

    @Test
    fun `a long cardio set waiting to be ticked stretches the idle limit by its minutes`() {
        val riding = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("stationary_bike", listOf(WorkoutSet(durationSeconds = 45 * 60)), restSeconds = 0, now = start)
        val limit = Duration.ofMinutes(30)

        assertFalse(riding.isIdle(start.plusSeconds(60 * 60), limit))
        assertTrue(riding.isIdle(start.plusSeconds(75 * 60), limit))
        assertTrue(workout().isIdle(start.plusSeconds(30 * 60), limit))
    }

    @Test
    fun `a coach target changes only the working sets not done yet`() {
        val warmup = WorkoutSet(type = SetType.WARMUP, weightKg = 20.0, reps = 5)
        val started = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", listOf(warmup) + sets(3, 60.0, 5), restSeconds = 120, now = start)
            .toggleSet(SetRef(0, 1), start)

        val sets = started.applyTarget("squat_barbell", weightKg = 62.5, reps = 3).exercises[0].sets

        assertEquals(listOf(20.0, 60.0, 62.5, 62.5), sets.map { it.weightKg })
        assertEquals(listOf(5, 5, 3, 3), sets.map { it.reps })
        assertEquals(listOf(5, 5, 3, 3), sets.map { it.targetReps })
        assertEquals(started, started.applyTarget("deadlift_barbell", 100.0, 5))
    }

    @Test
    fun `a target with only reps keeps each set's weight`() {
        val started = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", sets(1, 60.0, 5) + sets(1, 50.0, 5), restSeconds = 120, now = start)

        val sets = started.applyTarget("squat_barbell", weightKg = null, reps = 3).exercises[0].sets

        assertEquals(listOf(60.0, 50.0), sets.map { it.weightKg })
        assertEquals(listOf(3, 3), sets.map { it.reps })
    }

    @Test
    fun `a target goes to the first block of an exercise and leaves drop and failure sets alone`() {
        val drop = WorkoutSet(type = SetType.DROP, weightKg = 40.0, reps = 8)
        val failure = WorkoutSet(type = SetType.FAILURE, weightKg = 40.0, reps = 6)
        val started = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", sets(1, 60.0, 5) + drop + failure, restSeconds = 120, now = start)
            .addExercise("squat_barbell", sets(1, 50.0, 8), restSeconds = 120, now = start)

        val exercises = started.applyTarget("squat_barbell", weightKg = 62.5, reps = 5).exercises

        assertEquals(listOf(62.5, 40.0, 40.0), exercises[0].sets.map { it.weightKg })
        assertEquals(listOf(50.0), exercises[1].sets.map { it.weightKg })
    }

    @Test
    fun `a target's duration goes to open working sets only`() {
        val ride = WorkoutSet(durationSeconds = 600, level = 5)
        val started = ActiveWorkout(Workout(title = "Cardio", startedAt = start, exercises = emptyList()))
            .addExercise("treadmill", listOf(ride.copy(type = SetType.WARMUP), ride, ride), restSeconds = 0, now = start)
            .toggleSet(SetRef(0, 1), start)

        val sets = started.applyTarget("treadmill", weightKg = null, reps = null, durationSeconds = 1800).exercises[0].sets

        assertEquals(listOf(600, 600, 1800), sets.map { it.durationSeconds })
    }

    @Test
    fun `a target goes to the first block that still has open sets`() {
        val started = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", sets(1, 60.0, 5), restSeconds = 120, now = start)
            .addExercise("squat_barbell", sets(1, 50.0, 8), restSeconds = 120, now = start)
            .toggleSet(SetRef(0, 0), start)

        val exercises = started.applyTarget("squat_barbell", weightKg = 52.5, reps = 8).exercises

        assertEquals(listOf(60.0), exercises[0].sets.map { it.weightKg })
        assertEquals(listOf(52.5), exercises[1].sets.map { it.weightKg })
    }

    @Test
    fun `more working sets copy the last working set and go right after it, before a drop set`() {
        val drop = WorkoutSet(type = SetType.DROP, weightKg = 40.0, reps = 10)
        val started = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", sets(1, 60.0, 5) + sets(1, 62.5, 3) + drop, restSeconds = 120, now = start)
            .toggleSet(SetRef(0, 2), start)

        val grown = started.withWorkingSets("squat_barbell", 4)
        val sets = grown.exercises[0].sets

        assertEquals(listOf(60.0, 62.5, 62.5, 62.5, 40.0), sets.map { it.weightKg })
        assertEquals(listOf(5, 3, 3, 3, 10), sets.map { it.reps })
        assertEquals(listOf(SetType.NORMAL, SetType.NORMAL, SetType.NORMAL, SetType.NORMAL, SetType.DROP), sets.map { it.type })
        assertEquals(SetRef(0, 4), grown.shownCompletedSet)
    }

    @Test
    fun `dropping the open sets of the focused exercise moves focus to work, and the shown set follows its set`() {
        val drop = WorkoutSet(type = SetType.DROP, weightKg = 40.0, reps = 10)
        val started = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", sets(1, 60.0, 5) + sets(2, 62.5, 5) + drop, restSeconds = 120, now = start)
            .addExercise("overhead_press_barbell", sets(2, 30.0, 5), restSeconds = 90, now = start)
            .toggleSet(SetRef(0, 0), start)
            .toggleSet(SetRef(0, 3), start)

        val dropped = started.withWorkingSets("squat_barbell", 1)
        val removed = threeExercises().completeAll(1).focusExercise(0).withoutOpenSets("squat_barbell")

        assertEquals(listOf(SetType.NORMAL, SetType.DROP), dropped.exercises[0].sets.map { it.type })
        assertEquals(SetRef(0, 1), dropped.shownCompletedSet)
        assertEquals("overhead_press_barbell", dropped.focusedId())
        assertEquals("bench_press_barbell", removed.focusedId())
    }

    @Test
    fun `the same number of working sets, or none open to drop, leaves the workout as it was`() {
        val started = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", sets(2, 60.0, 5), restSeconds = 120, now = start)
            .toggleSet(SetRef(0, 0), start)
            .toggleSet(SetRef(0, 1), start)

        assertEquals(started, started.withWorkingSets("squat_barbell", 2))
        assertEquals(started, started.withWorkingSets("squat_barbell", 1))
    }

    @Test
    fun `fewer working sets drops open ones from the end, never a done set or a warm-up`() {
        val warmup = WorkoutSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10)
        val started = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", listOf(warmup) + sets(1, 60.0, 5) + sets(3, 62.5, 5), restSeconds = 120, now = start)
            .toggleSet(SetRef(0, 1), start)

        val two = started.withWorkingSets("squat_barbell", 2).exercises[0].sets
        val none = started.withWorkingSets("squat_barbell", 0).exercises[0].sets

        assertEquals(listOf(20.0, 60.0, 62.5), two.map { it.weightKg })
        assertEquals(listOf(20.0, 60.0), none.map { it.weightKg })
        assertTrue(none[1].isCompleted)
    }

    @Test
    fun `taking out an exercise removes it, or once a set is done keeps only the done sets`() {
        val started = threeExercises().toggleSet(SetRef(0, 0), start).focusExercise(2)

        val untouched = started.withoutOpenSets("overhead_press_barbell")
        val begun = started.withoutOpenSets("squat_barbell")

        assertEquals(listOf("squat_barbell", "bench_press_barbell"), untouched.exercises.map { it.exerciseId })
        assertEquals("bench_press_barbell", untouched.focusedId())
        assertEquals(1, begun.exercises[0].sets.size)
        assertTrue(begun.exercises[0].sets.single().isCompleted)
        assertEquals(3, begun.exercises.size)
        assertEquals(started, started.withoutOpenSets("deadlift_barbell"))
    }

    @Test
    fun `taking out the open sets of the focused exercise moves focus on, and an exercise already done stays as it is`() {
        val started = threeExercises().toggleSet(SetRef(0, 0), start)
        val done = threeExercises().completeAll(0)

        val trimmed = started.withoutOpenSets("squat_barbell")

        assertEquals("overhead_press_barbell", trimmed.focusedId())
        assertEquals(null, trimmed.shownCompletedSet)
        assertEquals(done, done.withoutOpenSets("squat_barbell"))
    }

    @Test
    fun `an added warm-up goes after the warm-ups already there and before the working sets`() {
        val warmup = WorkoutSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10)
        val started = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", listOf(warmup) + sets(2, 60.0, 5), restSeconds = 120, now = start)

        val sets = started.addWarmup(0, weightKg = 40.0).exercises[0].sets

        assertEquals(listOf(20.0, 40.0, 60.0, 60.0), sets.map { it.weightKg })
        assertEquals(listOf(SetType.WARMUP, SetType.WARMUP, SetType.NORMAL, SetType.NORMAL), sets.map { it.type })
        assertEquals(5, sets[1].reps)
    }

    @Test
    fun `an added warm-up keeps pointing at the set just done`() {
        val started = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
            .addExercise("squat_barbell", sets(2, 60.0, 5), restSeconds = 120, now = start)
            .toggleSet(SetRef(0, 0), start)

        assertEquals(SetRef(0, 1), started.addWarmup(0, weightKg = 40.0).shownCompletedSet)
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
    fun `the last completed set is the latest completion, not the last in the list, the last ticked or the latest activity`() {
        val done = workout()
            .toggleSet(SetRef(0, 2), start.plusSeconds(400))
            .toggleSet(SetRef(0, 0), start.plusSeconds(600))
            .toggleSet(SetRef(0, 1), start.plusSeconds(300))
            .addExercise("bench_press_barbell", sets(1, 40.0, 5), restSeconds = 60, now = start.plusSeconds(900))

        assertEquals(start.plusSeconds(600), done.lastCompletedAt())
        assertNull(workout().lastCompletedAt())
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

        val byScreen = byBand.complete(SetRef(0, 0), start.plusSeconds(1))

        assertEquals(byBand, byScreen)
        assertFalse(byScreen.exercises[0].sets[1].isCompleted)
    }

    @Test
    fun `completing an expected set completes that set even after focus moved`() {
        val done = workout().focusExercise(1).complete(SetRef(0, 0), start)

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

        val swapped = workout().completeFocusedSet(start).restOver().replaceExercise(0, "bench_press_barbell", replacementSets, restSeconds = 90)

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
        val after = resting.complete(expected!!, start.plusSeconds(30))

        assertEquals(SetRef(1, 0), expected)
        assertEquals(1, after.focus)
        assertTrue(after.exercises[1].sets[0].isCompleted)
    }

    @Test
    fun `play during rest within an exercise completes its next set only once`() {
        val resting = workout().completeFocusedSet(start)
        val expected = resting.nextSetToComplete()

        val once = resting.complete(expected!!, start.plusSeconds(30))
        val twice = once.complete(expected, start.plusSeconds(31))

        assertEquals(SetRef(0, 1), expected)
        assertEquals(2, once.exercises[0].sets.count { it.isCompleted })
        assertEquals(once.exercises, twice.exercises)
    }

    @Test
    fun `an RPE stays on the 6 to 10 scale and can be cleared`() {
        val ref = SetRef(0, 0)
        assertEquals(8.5, workout().setRpe(ref, 8.5).exercises[0].sets[0].rpe)
        assertEquals(10.0, workout().setRpe(ref, 12.0).exercises[0].sets[0].rpe)
        assertNull(workout().setRpe(ref, 8.0).setRpe(ref, null).exercises[0].sets[0].rpe)
    }
}
