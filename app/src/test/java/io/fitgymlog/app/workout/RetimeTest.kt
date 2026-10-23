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

    private val now = at(600)

    @Test
    fun `a workout left open can end at its last ticked set`() {
        val bounds = forgotten.timeBounds()

        assertEquals(TimeBounds(at(10), at(55)), bounds)
        assertNull(forgotten.retimeProblem(forgotten.startedAt, bounds.earliestEnd!!, now))
        assertEquals(at(55), forgotten.retimed(forgotten.startedAt, bounds.earliestEnd!!).endedAt)
    }

    @Test
    fun `times that would leave a ticked set outside the workout are refused`() {
        assertEquals(RetimeProblem.OUTSIDE_SETS, forgotten.retimeProblem(at(11), at(60), now))
        assertEquals(RetimeProblem.OUTSIDE_SETS, forgotten.retimeProblem(at(0), at(54), now))
    }

    @Test
    fun `times exactly at the first and last ticks are accepted`() {
        assertNull(forgotten.retimeProblem(at(10), at(55), now))
        assertEquals(listOf(at(10), at(55)), forgotten.retimed(at(10), at(55)).let { listOf(it.startedAt, it.endedAt) })
    }

    @Test
    fun `an end picked in the minute of the last tick keeps that tick inside`() {
        val lastTick = at(55).plusSeconds(45)
        val squat = WorkoutExercise(key = 1, exerciseId = "squat_barbell", restSeconds = 120, sets = listOf(WorkoutSet(reps = 5, completedAt = at(10)), WorkoutSet(reps = 5, completedAt = lastTick)))
        val ticked = forgotten.copy(exercises = listOf(squat))

        assertNull(ticked.retimeProblem(at(0), at(55), now))
        assertEquals(lastTick, ticked.retimed(at(0), at(55)).endedAt)
        assertEquals(RetimeProblem.OUTSIDE_SETS, ticked.retimeProblem(at(0), at(54), now))
    }

    @Test
    fun `the end must come after the start and not after now`() {
        val empty = forgotten.copy(exercises = emptyList())

        assertNull(empty.retimeProblem(at(90), at(100), now))
        assertEquals(RetimeProblem.ORDER, empty.retimeProblem(at(100), at(90), now))
        assertEquals(RetimeProblem.ORDER, empty.retimeProblem(at(90), at(90), now))
        assertEquals(RetimeProblem.FUTURE, empty.retimeProblem(at(90), at(601), now))
        assertNull(empty.retimeProblem(at(90), now, now))
    }
}
