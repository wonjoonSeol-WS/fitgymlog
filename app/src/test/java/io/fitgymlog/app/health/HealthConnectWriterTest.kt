package io.fitgymlog.app.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthConnectWriterTest {
    private val start = Instant.parse("2026-10-19T10:00:00Z")
    private val workout = Workout(id = 7, uuid = "0b1c9e", title = "Day A", startedAt = start, exercises = emptyList())

    private val exercises = listOf(
        Exercise("treadmill", "Treadmill", ExerciseKind.CARDIO, ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL),
        Exercise("squat_barbell", "Squat (Barbell)", ExerciseKind.WEIGHT_REPS, ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT),
        Exercise("stair_machine", "Stair Machine (Floors)", ExerciseKind.CARDIO, ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE),
    ).associateBy { it.id }

    private fun at(minutes: Long): Instant = start.plusSeconds(minutes * 60)

    private fun exercise(id: String, vararg sets: WorkoutSet) = WorkoutExercise(key = id.hashCode().toLong(), exerciseId = id, restSeconds = 0, sets = sets.toList())

    private fun sessions(workout: Workout) = sessionsOf(workout, ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, exercises::getValue) { it.englishName }

    @Test
    fun `records are keyed by the workout uuid, not the local row id`() {
        assertEquals("fitgymlog-workout-0b1c9e", clientRecordIdOf(workout))
    }

    @Test
    fun `a workout without a uuid gets no client record id`() {
        assertNull(clientRecordIdOf(workout.copy(uuid = "")))
    }

    @Test
    fun `a treadmill warm-up and a stair finisher are their own sessions, and lifting covers the time between`() {
        val day = workout.copy(
            endedAt = at(21),
            exercises = listOf(
                exercise("treadmill", WorkoutSet(durationSeconds = 300, completedAt = at(5))),
                exercise("squat_barbell", WorkoutSet(weightKg = 60.0, reps = 5, completedAt = at(8)), WorkoutSet(weightKg = 60.0, reps = 5, completedAt = at(11))),
                exercise("stair_machine", WorkoutSet(durationSeconds = 300, completedAt = at(20))),
            ),
        )

        val written = sessions(day).map { listOf(it.exerciseType, it.startTime, it.endTime, it.metadata.clientRecordId, it.title) }

        assertEquals(
            listOf(
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, at(5), at(15), "fitgymlog-workout-0b1c9e", "Day A"),
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, at(0), at(5), "fitgymlog-workout-0b1c9e-0-0", "Treadmill"),
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE, at(15), at(20), "fitgymlog-workout-0b1c9e-2-0", "Stair Machine (Floors)"),
            ),
            written,
        )
        assertEquals(listOf(ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT, ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT), sessions(day).first().segments.map { it.segmentType })
    }

    @Test
    fun `a workout without cardio is one session from start to finish`() {
        val day = workout.copy(endedAt = at(30), exercises = listOf(exercise("squat_barbell", WorkoutSet(weightKg = 60.0, reps = 5, completedAt = at(8)))))

        assertEquals(listOf(listOf(at(0), at(30))), sessions(day).map { listOf(it.startTime, it.endTime) })
    }

    @Test
    fun `cardio alone writes no lifting session, and a cardio set without a duration is skipped`() {
        val day = workout.copy(
            endedAt = at(12),
            exercises = listOf(exercise("treadmill", WorkoutSet(durationSeconds = 600, completedAt = at(10)), WorkoutSet(completedAt = at(11)))),
        )

        assertEquals(listOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL), sessions(day).map { it.exerciseType })
    }
}
