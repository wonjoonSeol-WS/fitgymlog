package io.fitgymlog.app.health

import io.fitgymlog.app.model.Workout
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthConnectWriterTest {
    private val workout = Workout(id = 7, uuid = "0b1c9e", title = "Day A", startedAt = Instant.parse("2026-10-19T10:38:00Z"), exercises = emptyList())

    @Test
    fun `records are keyed by the workout uuid, not the local row id`() {
        assertEquals("fitgymlog-workout-0b1c9e", clientRecordIdOf(workout))
    }

    @Test
    fun `a workout without a uuid gets no client record id`() {
        assertNull(clientRecordIdOf(workout.copy(uuid = "")))
    }
}
