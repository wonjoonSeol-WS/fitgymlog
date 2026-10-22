package io.fitgymlog.app.workout

import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.formatClock
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BandTextTest {
    private val start = Instant.parse("2026-10-19T10:38:00Z")

    private fun sets(count: Int, kg: Double, reps: Int) = List(count) { WorkoutSet(weightKg = kg, reps = reps) }

    private fun workout(): ActiveWorkout = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
        .addExercise("squat_barbell", sets(3, 60.0, 8), restSeconds = 120, now = start)
        .addExercise("overhead_press_barbell", sets(2, 30.0, 5), restSeconds = 90, now = start)

    @Test
    fun `resting counts down and shows the set that follows the rest`() {
        val resting = workout().lowerShownReps().completeFocusedSet(start)

        val screen = bandScreen(resting, start.plusSeconds(37))

        val rest = screen.rest!!
        assertEquals("1:23", formatClock(rest.left))
        assertEquals(Duration.ofSeconds(120), rest.length)
        assertEquals(Duration.ofSeconds(37), rest.elapsed)
        val next = screen.view!!
        assertEquals(2, next.setNumber)
        assertEquals(8, next.reps)
        assertFalse(next.isCompleted)
    }

    @Test
    fun `after rest ends the band shows the set again`() {
        val rested = workout().completeFocusedSet(start).restOver()

        val screen = bandScreen(rested, start.plusSeconds(200))

        assertNull(screen.rest)
        assertEquals(rested.bandView(), screen.view)
        assertEquals(2, screen.view!!.setNumber)
    }

    @Test
    fun `rest after an exercise's last set shows the next exercise's first set`() {
        var current = workout()
        repeat(2) { current = current.completeFocusedSet(start).restOver() }
        val resting = current.completeFocusedSet(start)

        val view = bandScreen(resting, start).view!!

        assertEquals("overhead_press_barbell", view.exerciseId)
        assertEquals(1, view.setNumber)
    }

    @Test
    fun `rest length runs from the set that started it, not from later activity`() {
        val resting = workout().completeFocusedSet(start.plusSeconds(10))
            .addExercise("bench_press_barbell", sets(1, 40.0, 5), restSeconds = 60, now = start.plusSeconds(30))

        val rest = bandScreen(resting, start.plusSeconds(40)).rest!!

        assertEquals(Duration.ofSeconds(120), rest.length)
        assertEquals(Duration.ofSeconds(30), rest.elapsed)
    }
}
