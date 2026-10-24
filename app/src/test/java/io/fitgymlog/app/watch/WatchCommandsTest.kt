package io.fitgymlog.app.watch

import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.SetRef
import io.fitgymlog.watchlink.WatchCommand
import io.fitgymlog.watchlink.WatchCommand.WorkoutEdit
import io.fitgymlog.watchlink.WatchSetRef
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchCommandsTest {
    private val start = Instant.parse("2026-10-23T10:00:00Z")

    private fun workout(): ActiveWorkout = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
        .addExercise("squat_barbell", List(3) { WorkoutSet(weightKg = 60.0, reps = 5) }, restSeconds = 90, now = start)
        .addExercise("overhead_press_barbell", List(2) { WorkoutSet(weightKg = 30.0, reps = 5) }, restSeconds = 90, now = start)

    private fun ActiveWorkout.apply(edit: WorkoutEdit, at: Instant = start) = applyWatch(edit, at)

    @Test
    fun `a rating after a finish that kept nothing rates nothing, not the workout before it`() = runTest {
        val earlier = suspend { 7L }

        assertNull(ratedWorkout(WatchFinish(null), earlier))
        assertEquals(9L, ratedWorkout(WatchFinish(9L), earlier))
        // With no Finish seen, as after a restart, the latest recent workout stands in.
        assertEquals(7L, ratedWorkout(null, earlier))
    }

    @Test
    fun `the check button logs the set as planned and starts the rest`() {
        val done = workout().apply(WatchCommand.Log(WatchSetRef(0, 0), complete = true))

        assertEquals(SetRef(0, 0), done.shownCompletedSet)
        assertEquals(start.plusSeconds(90), done.restEndsAt)
        assertEquals(5, done.exercises[0].sets[0].reps)
    }

    @Test
    fun `a second log for the same set counts once`() {
        val once = workout().apply(WatchCommand.Log(WatchSetRef(0, 0), complete = true))
        val twice = once.apply(WatchCommand.Log(WatchSetRef(0, 0), complete = true), start.plusSeconds(5))

        assertEquals(once, twice)
    }

    @Test
    fun `the dials log what was lifted, and a heavier weight carries to the later open sets`() {
        val done = workout().apply(WatchCommand.Log(WatchSetRef(0, 0), complete = true))
        val logged = done.apply(WatchCommand.Log(WatchSetRef(0, 1), weightKg = 62.5, reps = 4, complete = true))

        assertEquals(listOf(60.0, 62.5, 62.5), logged.exercises[0].sets.map { it.weightKg })
        assertEquals(listOf(5, 4, 5), logged.exercises[0].sets.map { it.reps })
        assertEquals(SetRef(0, 1), logged.shownCompletedSet)
    }

    @Test
    fun `correcting reps on a set already done keeps it done and leaves the rest running`() {
        val done = workout().apply(WatchCommand.Log(WatchSetRef(0, 0), complete = true))
        val corrected = done.apply(WatchCommand.Log(WatchSetRef(0, 0), reps = 3, complete = false))

        assertEquals(3, corrected.exercises[0].sets[0].reps)
        assertEquals(5, corrected.exercises[0].sets[0].targetReps)
        assertEquals(done.restEndsAt, corrected.restEndsAt)
    }

    @Test
    fun `a log for a set that no longer exists does nothing`() {
        assertEquals(workout(), workout().apply(WatchCommand.Log(WatchSetRef(5, 0), reps = 3, complete = true)))
    }

    @Test
    fun `swiping to an exercise moves the focus there`() {
        assertEquals(1, workout().apply(WatchCommand.Focus(1)).focus)
        assertEquals(0, workout().apply(WatchCommand.Focus(9)).focus)
    }

    @Test
    fun `rest can be shortened and skipped`() {
        val resting = workout().apply(WatchCommand.Log(WatchSetRef(0, 0), complete = true))

        assertEquals(start.plusSeconds(75), resting.apply(WatchCommand.AdjustRest(-15)).restEndsAt)
        assertNull(resting.apply(WatchCommand.SkipRest).restEndsAt)
    }
}
