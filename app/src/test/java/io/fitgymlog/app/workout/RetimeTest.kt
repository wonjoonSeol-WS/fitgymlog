package io.fitgymlog.app.workout

import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetimeTest {
    private val start = Instant.parse("2026-10-22T18:00:00Z")

    private fun at(minutes: Long): Instant = start.plusSeconds(minutes * 60)

    private val forgotten = Workout(
        uuid = "w-1",
        title = "Day A",
        startedAt = at(0),
        endedAt = at(240),
        exercises = listOf(
            WorkoutExercise(key = 1, exerciseId = "squat_barbell", restSeconds = 120, sets = listOf(WorkoutSet(weightKg = 60.0, reps = 5, completedAt = at(10)), WorkoutSet(weightKg = 60.0, reps = 5))),
            WorkoutExercise(key = 2, exerciseId = "bench_press_barbell", restSeconds = 120, sets = listOf(WorkoutSet(weightKg = 40.0, reps = 5, completedAt = at(55)))),
        ),
    )

    @Test
    fun `a workout left open can end at its last ticked set`() {
        val bounds = forgotten.timeBounds()

        assertEquals(TimeBounds(at(10), at(55)), bounds)
        assertEquals(at(55), forgotten.retimed(forgotten.startedAt, bounds.earliestEnd!!)?.endedAt)
    }

    @Test
    fun `times that would leave a ticked set outside the workout are refused`() {
        assertNull(forgotten.retimed(at(11), at(60)))
        assertNull(forgotten.retimed(at(0), at(54)))
        assertNull(forgotten.retimed(at(30), at(30)))
        assertEquals(listOf(at(10), at(55)), forgotten.retimed(at(10), at(55))?.let { listOf(it.startedAt, it.endedAt) })
    }

    @Test
    fun `a workout with nothing ticked can take any order-respecting times`() {
        val empty = forgotten.copy(exercises = emptyList())

        assertEquals(at(100), empty.retimed(at(90), at(100))?.endedAt)
        assertNull(empty.retimed(at(100), at(90)))
    }
}
