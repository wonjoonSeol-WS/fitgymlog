package io.fitgymlog.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappersTest {
    @Test
    fun `the previous sets of a timed exercise keep their duration and target`() {
        val plank = WorkoutSetEntity(
            workoutExerciseId = 1,
            position = 0,
            type = "NORMAL",
            weightKg = null,
            reps = null,
            targetReps = 8,
            rpe = null,
            distanceMeters = null,
            durationSeconds = 75,
            completedAt = 1_000L,
        )

        val previous = plank.toPreviousSet()

        assertEquals(75, previous.durationSeconds)
        assertEquals(8, previous.targetReps)
        assertNull(previous.completedAt)
    }
}
