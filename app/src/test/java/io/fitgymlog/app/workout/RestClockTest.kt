package io.fitgymlog.app.workout

import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.formatClock
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestClockTest {
    private val start = Instant.parse("2026-10-19T10:38:00Z")

    private fun sets(count: Int, kg: Double, reps: Int) = List(count) { WorkoutSet(weightKg = kg, reps = reps) }

    private fun workout(): ActiveWorkout = ActiveWorkout(Workout(title = "Day A", startedAt = start, exercises = emptyList()))
        .addExercise("squat_barbell", sets(3, 60.0, 8), restSeconds = 120, now = start)
        .addExercise("overhead_press_barbell", sets(2, 30.0, 5), restSeconds = 90, now = start)

    @Test
    fun `resting counts down over the set just done, as corrected by previous`() {
        val resting = workout().completeFocusedSet(start).lowerShownReps()

        val rest = resting.restClock(start.plusSeconds(37))!!
        assertEquals("1:23", formatClock(rest.left))
        assertEquals(Duration.ofSeconds(120), rest.length)
        assertEquals(Duration.ofSeconds(37), rest.elapsed)
        val done = resting.bandView()!!
        assertEquals(1, done.setNumber)
        assertEquals(7, done.reps)
        assertTrue(done.isCompleted)
    }

    @Test
    fun `after rest ends the band shows the set again`() {
        val rested = workout().completeFocusedSet(start).restOver()

        assertNull(rested.restClock(start.plusSeconds(200)))
        assertEquals(2, rested.bandView()!!.setNumber)
    }

    @Test
    fun `rest after an exercise's last set still shows that set`() {
        var current = workout()
        repeat(2) { current = current.completeFocusedSet(start).restOver() }
        val resting = current.completeFocusedSet(start)

        val view = resting.bandView()!!

        assertEquals("squat_barbell", view.exerciseId)
        assertEquals(3, view.setNumber)
        assertTrue(view.isCompleted)
    }

    @Test
    fun `rest length runs from the set that started it, not from later activity`() {
        val resting = workout().completeFocusedSet(start.plusSeconds(10))
            .addExercise("bench_press_barbell", sets(1, 40.0, 5), restSeconds = 60, now = start.plusSeconds(30))

        val rest = resting.restClock(start.plusSeconds(40))!!

        assertEquals(Duration.ofSeconds(120), rest.length)
        assertEquals(Duration.ofSeconds(30), rest.elapsed)
    }

    @Test
    fun `the band counts rest down in whole five second steps, rounding up`() {
        assertEquals(Duration.ofSeconds(120), bandRestLeft(Duration.ofMillis(119_990)))
        assertEquals(Duration.ofSeconds(115), bandRestLeft(Duration.ofMillis(115_000)))
        assertEquals(Duration.ofSeconds(115), bandRestLeft(Duration.ofMillis(110_001)))
        assertEquals(Duration.ofSeconds(5), bandRestLeft(Duration.ofMillis(1)))
        assertEquals(Duration.ZERO, bandRestLeft(Duration.ZERO))
    }

    @Test
    fun `the next band update comes when the rest left reaches a multiple of five seconds`() {
        assertEquals(Duration.ofMillis(4_990), untilNextBandStep(Duration.ofMillis(119_990)))
        assertEquals(Duration.ofSeconds(2), untilNextBandStep(Duration.ofSeconds(37)))
        assertEquals(Duration.ZERO, untilNextBandStep(Duration.ofSeconds(115)))
    }
}
